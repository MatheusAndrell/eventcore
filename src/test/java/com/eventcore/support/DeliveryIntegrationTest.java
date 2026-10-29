package com.eventcore.support;

import static org.awaitility.Awaitility.await;

import com.eventcore.access.application.ClientAdministration;
import com.eventcore.access.domain.ClientName;
import com.eventcore.access.domain.Scope;
import com.eventcore.ingestion.application.ReceiveEvent;
import com.eventcore.ingestion.application.ReceiveEventCommand;
import com.eventcore.ingestion.application.ReceiveEventResult;
import com.eventcore.ingestion.domain.IdempotencyKey;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.identity.CorrelationId;
import com.eventcore.platform.json.StrictJsonReader;
import com.eventcore.subscription.application.CreatedSubscription;
import com.eventcore.subscription.application.SubscriptionManagement;
import com.eventcore.subscription.domain.SubscribedEventTypes;
import com.eventcore.subscription.domain.WebhookEndpoint;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eventcore.roles=api,relay,worker",
                "management.server.port=0",
                "eventcore.outbox.poll-interval=50ms",
                "eventcore.delivery.connect-timeout=1s",
                "eventcore.delivery.response-timeout=1s",
                "eventcore.delivery.request-timeout=2s",
                "eventcore.delivery.lease=10s",
                "eventcore.delivery.max-attempts=3",
                "eventcore.delivery.initial-retry-delay=100ms",
                "eventcore.delivery.max-retry-delay=200ms"})
@Import({TestcontainersConfiguration.class, StaticHostResolverConfiguration.class,
        ReceiverEgressConfiguration.class})
public abstract class DeliveryIntegrationTest {

    protected static final JsonMapper JSON = JsonMapper.builder().build();
    protected static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);
    protected static final WebhookReceiver receiver = WebhookReceiver.shared();

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected ClientAdministration administration;

    @Autowired
    protected SubscriptionManagement subscriptions;

    @Autowired
    protected ReceiveEvent receiveEvent;

    @LocalServerPort
    private int port;

    protected ClientId client;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void receiverProperties(DynamicPropertyRegistry registry) {
        ReceiverEgressConfiguration.registerReceiver(registry, receiver);
    }

    @BeforeEach
    void resetState() {
        jdbc.sql("truncate outbox_message, idempotency_record, event, api_key, client cascade").update();
        receiver.reset();
        client = administration.createClient(new ClientName("acme")).id();
    }

    protected CreatedSubscription subscribe(String path, String... eventTypes) {
        return subscriptions.create(client, WebhookEndpoint.parse(receiver.url(path)),
                SubscribedEventTypes.of(List.of(eventTypes)));
    }

    protected UUID receivePayment(String idempotencyKey) {
        ReceiveEventResult result = receiveEvent.receive(new ReceiveEventCommand(client,
                new IdempotencyKey(idempotencyKey), new CorrelationId("delivery-it"),
                new StrictJsonReader().read("""
                        {"type": "payment.captured", "dataversion": "1", "time": "2026-10-20T12:00:00Z",
                         "source": "urn:acme:payments", "data": {"paymentId": "p-1", "orderId": "o-1",
                         "amount": "15.00", "currency": "BRL"}}
                        """.getBytes(StandardCharsets.UTF_8))));
        return UUID.fromString(((ReceiveEventResult.Accepted) result).receipt().id().toString());
    }

    protected List<Map<String, Object>> awaitDeliveries(UUID eventId, int expected) {
        await().atMost(DELIVERY_TIMEOUT).until(() -> jdbc.sql("""
                        select count(*) from outbox_message where status = 'PENDING'
                        """).query(Long.class).single() == 0
                && jdbc.sql("select count(*) from delivery where event_id = :eventId and status <> 'PENDING'")
                        .param("eventId", eventId).query(Long.class).single() == expected
                && jdbc.sql("select count(*) from delivery where event_id = :eventId")
                        .param("eventId", eventId).query(Long.class).single() == expected);
        return deliveries(eventId);
    }

    protected List<Map<String, Object>> deliveries(UUID eventId) {
        return jdbc.sql("""
                        select id, subscription_id, status, attempts, max_attempts, last_error
                          from delivery where event_id = :id order by created_at, id
                        """)
                .param("id", eventId)
                .query()
                .listOfRows();
    }

    protected List<Integer> attemptStatuses(UUID deliveryId) {
        return jdbc.sql("""
                        select coalesce(http_status, 0) from delivery_attempt
                         where delivery_id = :id order by attempt_number
                        """)
                .param("id", deliveryId)
                .query(Integer.class)
                .list();
    }

    protected String issueKey(Scope... scopes) {
        return administration.issueApiKey(client, Set.of(scopes), Optional.empty()).orElseThrow().token().value();
    }

    protected HttpResponse<String> get(String path, String apiKey) {
        return send(request(path, apiKey).GET());
    }

    protected HttpResponse<String> post(String path, String apiKey) {
        return send(request(path, apiKey).POST(HttpRequest.BodyPublishers.noBody()));
    }

    protected HttpResponse<String> post(String path, String apiKey, String jsonBody) {
        return send(request(path, apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    protected static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private HttpRequest.Builder request(String path, String apiKey) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("HTTP request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted", exception);
        }
    }

}
