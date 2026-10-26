package com.eventcore.platform.egress;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class EgressPolicy {

    private static final String HTTPS = "https";
    private static final int HTTPS_PORT = 443;
    private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9.]+");

    private final HostResolver resolver;
    private final Set<Integer> allowedPorts;

    public EgressPolicy(HostResolver resolver, EgressProperties properties) {
        this.resolver = resolver;
        this.allowedPorts = Set.copyOf(properties.allowedPorts());
    }

    public void check(URI uri) {
        if (!HTTPS.equalsIgnoreCase(uri.getScheme())) {
            throw new EgressDeniedException("only https destinations are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw new EgressDeniedException("destinations must not contain user information");
        }
        int port = uri.getPort() == -1 ? HTTPS_PORT : uri.getPort();
        if (!allowedPorts.contains(port)) {
            throw new EgressDeniedException("port " + port + " is not an allowed destination port");
        }
        if (uri.getHost() == null) {
            throw new EgressDeniedException("destinations must have a host name");
        }
        resolvePublic(uri.getHost());
    }

    public List<InetAddress> resolvePublic(String host) {
        List<InetAddress> addresses = isLiteral(host) ? List.of(literal(host)) : resolveName(host);
        if (addresses.stream().anyMatch(NonPublicAddresses::includes)) {
            throw new EgressDeniedException("host resolves to a non-public address");
        }
        return addresses;
    }

    private static boolean isLiteral(String host) {
        return host.startsWith("[") || host.indexOf(':') >= 0 || IPV4_LITERAL.matcher(host).matches();
    }

    private static InetAddress literal(String host) {
        String address = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            return InetAddress.ofLiteral(address);
        } catch (IllegalArgumentException exception) {
            throw new EgressDeniedException("host is not a valid IP address", exception);
        }
    }

    private List<InetAddress> resolveName(String host) {
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (name.indexOf('.') < 0) {
            throw new EgressDeniedException("host must be a fully qualified domain name");
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(name);
        } catch (UnknownHostException exception) {
            throw new EgressDeniedException("host could not be resolved", exception);
        }
        if (addresses.isEmpty()) {
            throw new EgressDeniedException("host could not be resolved");
        }
        return List.copyOf(addresses);
    }
}
