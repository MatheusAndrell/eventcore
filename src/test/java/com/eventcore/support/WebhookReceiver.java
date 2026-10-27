package com.eventcore.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public final class WebhookReceiver implements AutoCloseable {

    public static final String HOST = "receiver.test";
    private static final String ALIAS = "receiver";

    private static final Duration TRICKLE_INTERVAL = Duration.ofMillis(200);

    private final HttpsServer server;
    private final ServerSocket trickling;
    private final AtomicInteger trickledRequests = new AtomicInteger();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Path trustStore;
    private final String password;
    private final List<ReceivedWebhook> received = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();

    private WebhookReceiver(Path directory, String password) throws IOException, GeneralSecurityException {
        this.password = password;
        Path keyStore = directory.resolve("receiver.p12");
        Path certificate = directory.resolve("receiver.pem");
        this.trustStore = directory.resolve("trust.p12");
        keytool("-genkeypair", "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
                "-dname", "CN=" + HOST, "-ext", "SAN=dns:" + HOST, "-keystore", keyStore.toString(),
                "-storetype", "PKCS12", "-storepass", password, "-keypass", password);
        keytool("-exportcert", "-rfc", "-alias", ALIAS, "-keystore", keyStore.toString(), "-storepass", password,
                "-file", certificate.toString());
        keytool("-importcert", "-noprompt", "-alias", ALIAS, "-file", certificate.toString(),
                "-keystore", trustStore.toString(), "-storetype", "PKCS12", "-storepass", password);
        SSLContext sslContext = sslContext(keyStore, password);
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        trickling = sslContext.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        executor.submit(this::acceptTricklingConnections);
    }

    public static WebhookReceiver start() {
        try {
            byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            return new WebhookReceiver(Files.createTempDirectory("webhook-receiver"), HexFormat.of().formatHex(secret));
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("could not start the webhook receiver", exception);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url(String path) {
        return "https://" + HOST + ":" + port() + path;
    }

    public int tricklingPort() {
        return trickling.getLocalPort();
    }

    public String tricklingUrl(String path) {
        return "https://" + HOST + ":" + tricklingPort() + path;
    }

    public int trickledRequests() {
        return trickledRequests.get();
    }

    public Path trustStore() {
        return trustStore;
    }

    public String trustStorePassword() {
        return password;
    }

    public void reply(String path, int status) {
        replies.put(path, new Reply(status, Duration.ZERO, Optional.empty()));
    }

    public void replySlowly(String path, Duration delay) {
        replies.put(path, new Reply(204, delay, Optional.empty()));
    }

    public void redirect(String path, String location) {
        replies.put(path, new Reply(302, Duration.ZERO, Optional.of(location)));
    }

    public List<ReceivedWebhook> received() {
        return List.copyOf(received);
    }

    public List<ReceivedWebhook> received(String path) {
        return received.stream().filter(webhook -> webhook.path().equals(path)).toList();
    }

    public void reset() {
        received.clear();
        replies.clear();
        trickledRequests.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
        try {
            trickling.close();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            executor.shutdownNow();
        }
    }

    private void acceptTricklingConnections() {
        while (!trickling.isClosed()) {
            Socket socket;
            try {
                socket = trickling.accept();
            } catch (IOException exception) {
                if (trickling.isClosed()) {
                    return;
                }
                throw new UncheckedIOException(exception);
            }
            executor.submit(() -> trickle(socket));
        }
    }

    private Void trickle(Socket socket) throws IOException, InterruptedException {
        try (socket; InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream()) {
            int matched = 0;
            byte[] terminator = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            while (matched < terminator.length) {
                int next = input.read();
                if (next < 0) {
                    return null;
                }
                matched = next == terminator[matched] ? matched + 1 : (next == terminator[0] ? 1 : 0);
            }
            trickledRequests.incrementAndGet();
            byte[] status = "HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.US_ASCII);
            for (int index = 0; !socket.isClosed(); index++) {
                output.write(index < status.length ? status[index] : 'x');
                output.flush();
                Thread.sleep(TRICKLE_INTERVAL);
            }
            return null;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = input.readAllBytes();
        }
        Map<String, String> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        String path = exchange.getRequestURI().getPath();
        received.add(new ReceivedWebhook(exchange.getRequestMethod(), path, headers, body));
        Reply reply = replies.getOrDefault(path, new Reply(204, Duration.ZERO, Optional.empty()));
        if (!reply.delay().isZero()) {
            try {
                Thread.sleep(reply.delay());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        reply.location().ifPresent(location -> exchange.getResponseHeaders().set("Location", location));
        exchange.sendResponseHeaders(reply.status(), -1);
        exchange.close();
    }

    private static SSLContext sslContext(Path keyStorePath, String password)
            throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, password.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private static void keytool(String... arguments) throws IOException {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "keytool.exe"
                : "keytool";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("keytool " + arguments[0] + " failed: " + output);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running keytool", exception);
        }
    }

    public record ReceivedWebhook(String method, String path, Map<String, String> headers, byte[] body) {

        public String header(String name) {
            return headers.get(name);
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private record Reply(int status, Duration delay, Optional<String> location) {
    }
}
