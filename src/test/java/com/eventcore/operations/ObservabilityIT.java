package com.eventcore.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventcore.access.domain.Scope;
import com.eventcore.support.DeliveryIntegrationTest;
import com.eventcore.support.WebhookReceiver.ReceivedWebhook;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

@AutoConfigureTracing
@AutoConfigureMetrics
@TestPropertySource(properties = "management.tracing.sampling.probability=1.0")
class ObservabilityIT extends DeliveryIntegrationTest {

    private static final String EVENT = """
            {"type": "payment.captured", "dataversion": "1", "time": "2026-10-20T12:00:00Z",
             "source": "urn:acme:payments", "data": {"paymentId": "p-7", "orderId": "o-7",
             "amount": "7.00", "currency": "BRL"}}
            """;

    @Autowired
    private InMemorySpanExporter spans;

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearSpans() {
        spans.reset();
    }

    @Test
    void continuesTheCallersTraceThroughTheOutboxQueuesAndWebhookDelivery() throws Exception {
        subscribe("/traced", "payment.captured");
        String traceId = HexFormat.of().formatHex(randomBytes(16));
        String traceparent = "00-" + traceId + "-" + HexFormat.of().formatHex(randomBytes(8)) + "-01";

        HttpResponse<String> accepted = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/v1/events"))
                .header("Authorization", "Bearer " + issueKey(Scope.EVENTS_WRITE))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "traced-1")
                .header("traceparent", traceparent)
                .POST(HttpRequest.BodyPublishers.ofString(EVENT))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(accepted.statusCode()).isEqualTo(201);
        UUID eventId = UUID.fromString(json(accepted).get("id").stringValue());
        awaitDeliveries(eventId, 1);
        await().atMost(DELIVERY_TIMEOUT).untilAsserted(() -> {
            List<SpanData> trace = spans.getFinishedSpanItems().stream()
                    .filter(span -> span.getTraceId().equals(traceId))
                    .toList();
            assertThat(trace).filteredOn(span -> span.getKind() == SpanKind.SERVER).isNotEmpty();
            assertThat(trace).filteredOn(span -> span.getKind() == SpanKind.CONSUMER).hasSizeGreaterThanOrEqualTo(2);
            assertThat(trace).extracting(SpanData::getName).contains("webhook delivery");
        });
        ReceivedWebhook webhook = receiver.received("/traced").getFirst();
        assertThat(webhook.headers()).doesNotContainKeys("traceparent", "tracestate", "b3");
    }

    @Test
    void exposesOperationalMetricsOnTheManagementPort() throws IOException, InterruptedException {
        subscribe("/measured", "payment.captured");
        awaitDeliveries(receivePayment("measured-1"), 1);

        HttpResponse<String> scrape = http.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                        + managementPort + "/actuator/prometheus"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains(
                "eventcore_events_received_total{outcome=\"accepted\",reason=\"none\"",
                "eventcore_outbox_published_total{outcome=\"confirmed\"",
                "eventcore_outbox_pending ",
                "eventcore_outbox_oldest_pending_age_seconds ",
                "eventcore_deliveries_outcomes_total{outcome=\"succeeded\"",
                "eventcore_deliveries_pending ",
                "eventcore_deliveries_due ",
                "eventcore_webhook_request_seconds_count{",
                "outcome=\"2xx\"",
                "eventcore_parking_messages ");
        assertThat(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isNotEqualTo(200);
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SpanCapture {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }
}
