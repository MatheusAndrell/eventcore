package com.eventcore.delivery.infrastructure;

import com.eventcore.delivery.application.DeliveryProperties;
import com.eventcore.delivery.application.TransportResult;
import com.eventcore.delivery.application.WebhookRequest;
import com.eventcore.delivery.application.WebhookTransport;
import com.eventcore.platform.egress.EgressDeniedException;
import com.eventcore.platform.egress.EgressPolicy;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnRole(Role.WORKER)
class HttpClientWebhookTransport implements WebhookTransport, DisposableBean {

    static final String USER_AGENT = "EventCore-Webhooks/1";
    private static final String OBSERVATION = "eventcore.webhook.request";

    private final EgressPolicy egressPolicy;
    private final ObservationRegistry observations;
    private final Clock clock;
    private final Duration requestTimeout;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("webhook-deadlines").daemon().factory());

    HttpClientWebhookTransport(
            EgressPolicy egressPolicy,
            DeliveryProperties properties,
            SslBundles sslBundles,
            Clock clock,
            ObservationRegistry observations) {
        this.egressPolicy = egressPolicy;
        this.observations = observations;
        this.clock = clock;
        this.requestTimeout = properties.requestTimeout();
        SSLContext sslContext = properties.configuredSslBundle()
                .map(bundle -> sslBundles.getBundle(bundle).createSslContext())
                .orElseGet(SSLContexts::createSystemDefault);
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new EgressDnsResolver(egressPolicy))
                .setTlsSocketStrategy(new DefaultClientTlsStrategy(sslContext))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                        .setSocketTimeout(Timeout.of(properties.responseTimeout()))
                        .build())
                .setMaxConnTotal(properties.maxConnections())
                .setMaxConnPerRoute(properties.maxConnectionsPerEndpoint())
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(properties.responseTimeout()))
                        .setConnectionRequestTimeout(Timeout.of(properties.connectTimeout()))
                        .setRedirectsEnabled(false)
                        .build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableAutomaticRetries()
                .disableContentCompression()
                .setUserAgent(USER_AGENT)
                .build();
    }

    @Override
    public TransportResult send(WebhookRequest request) {
        Observation observation = Observation.createNotStarted(OBSERVATION, observations)
                .contextualName("webhook delivery")
                .start();
        try (Observation.Scope _ = observation.openScope()) {
            TransportResult result = post(request);
            observation.lowCardinalityKeyValue("outcome", outcome(result));
            return result;
        } finally {
            observation.stop();
        }
    }

    private static String outcome(TransportResult result) {
        return switch (result) {
            case TransportResult.Responded responded -> (responded.statusCode() / 100) + "xx";
            case TransportResult.Failed _ -> "transport_error";
        };
    }

    private TransportResult post(WebhookRequest request) {
        try {
            egressPolicy.check(request.endpoint());
        } catch (EgressDeniedException exception) {
            return denied(exception);
        }
        HttpPost post = new HttpPost(request.endpoint());
        request.headers().forEach(post::setHeader);
        post.setEntity(new ByteArrayEntity(request.body(), ContentType.APPLICATION_JSON));
        ScheduledFuture<?> deadline = deadlines.schedule(post::cancel, requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        try (ClassicHttpResponse response = client.executeOpen(null, post, null)) {
            return new TransportResult.Responded(response.getCode(),
                    RetryAfterHeader.parse(response.getFirstHeader(HttpHeaders.RETRY_AFTER), clock.instant()));
        } catch (EgressDeniedException exception) {
            return denied(exception);
        } catch (IOException exception) {
            if (post.isCancelled()) {
                return new TransportResult.Failed("request exceeded the " + requestTimeout.toMillis() + " ms deadline");
            }
            return new TransportResult.Failed(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        } finally {
            deadline.cancel(false);
        }
    }

    @Override
    public void destroy() throws IOException {
        deadlines.shutdownNow();
        client.close();
    }

    private static TransportResult denied(EgressDeniedException exception) {
        return new TransportResult.Failed("destination not allowed: " + exception.getMessage());
    }
}
