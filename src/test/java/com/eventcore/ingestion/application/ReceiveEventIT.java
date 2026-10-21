package com.eventcore.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventcore.ingestion.domain.IdempotencyKey;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.identity.CorrelationId;
import com.eventcore.platform.json.StrictJsonReader;
import com.eventcore.support.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"eventcore.roles=api", "eventcore.ingestion.idempotency-lock-timeout=500ms"})
@Import({TestcontainersConfiguration.class, ReceiveEventIT.NanosecondClockConfiguration.class})
class ReceiveEventIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class NanosecondClockConfiguration {

        @Bean
        @Primary
        Clock nanosecondClock() {
            return Clock.offset(Clock.systemUTC(), Duration.ofNanos(123));
        }
    }

    private static final ClientId CLIENT = new ClientId(UUID.fromString("0192f3c8-0000-7000-8000-000000000001"));
    private static final ClientId OTHER_CLIENT = new ClientId(UUID.fromString("0192f3c8-0000-7000-8000-000000000002"));
    private static final CorrelationId CORRELATION = new CorrelationId("receive-event-it");

    @Autowired
    private ReceiveEvent receiveEvent;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    private final StrictJsonReader reader = new StrictJsonReader();

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("truncate outbox_message, idempotency_record, event, api_key, client cascade").update();
        insertClient(CLIENT, "acme");
        insertClient(OTHER_CLIENT, "globex");
    }

    @Test
    void storesTheEventItsIdempotencyRecordAndTheOutboxMessageTogether() {
        ReceiveEventResult result = receive(CLIENT, "key-1", orderPlaced("o-1"));

        assertThat(result).isInstanceOf(ReceiveEventResult.Accepted.class);
        EventReceipt receipt = ((ReceiveEventResult.Accepted) result).receipt();
        Map<String, Object> event = jdbc.sql("""
                        select type, data_version, source, subject, status, data::text as data, occurred_at
                          from event where id = :id
                        """)
                .param("id", receipt.id().value()).query().singleRow();
        assertThat(event).containsEntry("type", "order.placed")
                .containsEntry("data_version", 2)
                .containsEntry("source", "urn:acme:orders")
                .containsEntry("subject", "order/o-1")
                .containsEntry("status", "RECEIVED");
        assertThat(reader.read(((String) event.get("data")).getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(json(orderPlaced("o-1")).get("data"));
        assertThat(((Timestamp) event.get("occurred_at")).toInstant())
                .isEqualTo(Instant.parse("2026-10-20T12:00:00Z"));

        Map<String, Object> idempotency = jdbc.sql("""
                        select event_id, octet_length(request_fingerprint) as fingerprint_length,
                               extract(epoch from expires_at - created_at)::bigint as retention_seconds
                          from idempotency_record where client_id = :client and idempotency_key = 'key-1'
                        """)
                .param("client", CLIENT.value()).query().singleRow();
        assertThat(idempotency.get("event_id")).isEqualTo(receipt.id().value());
        assertThat(idempotency.get("fingerprint_length")).isEqualTo(32);
        assertThat(idempotency.get("retention_seconds")).isEqualTo(Duration.ofHours(24).toSeconds());

        Map<String, Object> outbox = jdbc.sql("""
                        select aggregate_type, aggregate_id, exchange, routing_key, message_type, message_version,
                               payload->>'eventId' as event_id, status, available_at = created_at as available_now
                          from outbox_message
                        """).query().singleRow();
        assertThat(outbox).containsEntry("aggregate_type", "event")
                .containsEntry("aggregate_id", receipt.id().value())
                .containsEntry("exchange", "eventcore.events")
                .containsEntry("routing_key", "event.accepted")
                .containsEntry("message_type", "event.accepted")
                .containsEntry("message_version", 1)
                .containsEntry("event_id", receipt.id().toString())
                .containsEntry("status", "PENDING")
                .containsEntry("available_now", true);
    }

    @Test
    void replaysTheOriginalReceiptForAnEquivalentRequest() {
        ReceiveEventResult first = receive(CLIENT, "key-1", orderPlaced("o-1"));
        String equivalent = """
                {
                  "data": {"items": [{"unitPrice": {"currency": "BRL", "amount": "15.00"},
                                      "quantity": 1, "sku": "sku-1"}],
                           "total": {"currency": "BRL", "amount": "15.00"},
                           "customerId": "c-1", "orderId": "\\u006f-1"},
                  "subject": "order/o-1", "source": "urn:acme:orders",
                  "time": "2026-10-20T12:00:00Z", "dataversion": "2", "type": "order.placed"
                }
                """;

        ReceiveEventResult second = receive(CLIENT, "key-1", equivalent);

        assertThat(second).isEqualTo(new ReceiveEventResult.Replayed(((ReceiveEventResult.Accepted) first).receipt()));
        assertCounts(1, 1, 1);
    }

    @Test
    void rejectsReuseOfAKeyWithDifferentContent() {
        receive(CLIENT, "key-1", orderPlaced("o-1"));

        ReceiveEventResult reused = receive(CLIENT, "key-1", orderPlaced("o-2"));

        assertThat(reused).isEqualTo(new ReceiveEventResult.IdempotencyKeyReused());
        assertCounts(1, 1, 1);
    }

    @Test
    void scopesKeysToTheClient() {
        ReceiveEventResult first = receive(CLIENT, "shared-key", orderPlaced("o-1"));
        ReceiveEventResult second = receive(OTHER_CLIENT, "shared-key", orderPlaced("o-1"));

        assertThat(first).isInstanceOf(ReceiveEventResult.Accepted.class);
        assertThat(second).isInstanceOf(ReceiveEventResult.Accepted.class);
        assertThat(((ReceiveEventResult.Accepted) first).receipt().id())
                .isNotEqualTo(((ReceiveEventResult.Accepted) second).receipt().id());
        assertCounts(2, 2, 2);
    }

    @Test
    void createsASingleEventWhenIdenticalRequestsRace() throws Exception {
        List<ReceiveEventResult> results = race(16, attempt -> orderPlaced("o-1"));

        assertThat(results).filteredOn(ReceiveEventResult.Accepted.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(ReceiveEventResult.Replayed.class::isInstance).hasSize(15);
        assertThat(results).extracting(ReceiveEventIT::receiptId).containsOnly(receiptId(results.getFirst()));
        assertCounts(1, 1, 1);
    }

    @Test
    void createsASingleEventWhenDifferentRequestsRaceForTheSameKey() throws Exception {
        List<ReceiveEventResult> results = race(16, attempt -> orderPlaced("o-" + attempt));

        assertThat(results).filteredOn(ReceiveEventResult.Accepted.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(result -> !(result instanceof ReceiveEventResult.Accepted))
                .allSatisfy(result -> assertThat(result).isInstanceOfAny(
                        ReceiveEventResult.IdempotencyKeyReused.class,
                        ReceiveEventResult.ConcurrentRequestInProgress.class));
        assertCounts(1, 1, 1);
    }

    @Test
    void reportsARequestInProgressWhenTheKeyStaysLockedBeyondTheTimeout() throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement("""
                    insert into idempotency_record (
                        client_id, idempotency_key, request_fingerprint, event_id, created_at, expires_at)
                    values (?, 'key-1', decode(repeat('00', 32), 'hex'), gen_random_uuid(), now(), now() + interval '1 day')
                    """)) {
                statement.setObject(1, CLIENT.value());
                statement.executeUpdate();
            }

            long startedAt = System.nanoTime();
            ReceiveEventResult whileLocked = receive(CLIENT, "key-1", orderPlaced("o-1"));
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(whileLocked).isEqualTo(new ReceiveEventResult.ConcurrentRequestInProgress());
            assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(450));
            holder.rollback();
        }

        assertThat(receive(CLIENT, "key-1", orderPlaced("o-1"))).isInstanceOf(ReceiveEventResult.Accepted.class);
        assertCounts(1, 1, 1);
    }

    @Test
    void acceptsDeprecatedVersions() {
        String body = """
                {"type": "order.placed", "dataversion": "1", "time": "2026-10-20T12:00:00Z",
                 "source": "urn:acme:orders", "data": {"orderId": "o-1", "customerId": "c-1", "totalCents": 1500}}
                """;

        assertThat(receive(CLIENT, "key-1", body)).isInstanceOf(ReceiveEventResult.Accepted.class);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "UNKNOWN_EVENT_TYPE|{\"type\":\"order.cancelled\",\"dataversion\":\"1\",\"time\":\"2026-10-20T12:00:00Z\",\"source\":\"urn:a\",\"data\":{}}",
            "UNSUPPORTED_EVENT_VERSION|{\"type\":\"customer.registered\",\"dataversion\":\"1\",\"time\":\"2026-10-20T12:00:00Z\",\"source\":\"urn:a\",\"data\":{\"customerId\":\"c\",\"name\":\"n\"}}",
            "UNSUPPORTED_EVENT_VERSION|{\"type\":\"order.placed\",\"dataversion\":\"9\",\"time\":\"2026-10-20T12:00:00Z\",\"source\":\"urn:a\",\"data\":{}}",
            "INVALID_EVENT_DATA|{\"type\":\"payment.captured\",\"dataversion\":\"1\",\"time\":\"2026-10-20T12:00:00Z\",\"source\":\"urn:a\",\"data\":{\"paymentId\":\"p\"}}",
            "MALFORMED_ENVELOPE|{\"type\":\"payment.captured\",\"dataversion\":\"1\",\"time\":\"2026-10-20T12:00:00Z\",\"source\":\"urn:a\",\"data\":{},\"id\":\"mine\"}",
            "OCCURRED_IN_FUTURE|{\"type\":\"order.placed\",\"dataversion\":\"1\",\"time\":\"2999-01-01T00:00:00Z\",\"source\":\"urn:a\",\"data\":{\"orderId\":\"o\",\"customerId\":\"c\",\"totalCents\":1}}",
            "NUMBER_NOT_REPRESENTABLE|{\"type\":\"order.placed\",\"dataversion\":\"1\",\"time\":\"2026-10-20T12:00:00Z\",\"source\":\"urn:a\",\"data\":{\"orderId\":\"o\",\"customerId\":\"c\",\"totalCents\":9007199254740993}}",
            "INVALID_STRING|{\"type\":\"order.placed\",\"dataversion\":\"1\",\"time\":\"2026-10-20T12:00:00Z\",\"source\":\"urn:a\",\"data\":{\"orderId\":\"\\ud800\",\"customerId\":\"c\",\"totalCents\":1}}"
    })
    void rejectsInvalidSubmissionsWithoutConsumingTheKey(Rejection expected, String body) {
        ReceiveEventResult result = receive(CLIENT, "key-1", body);

        assertThat(result).isInstanceOfSatisfying(ReceiveEventResult.Rejected.class, rejected -> {
            assertThat(rejected.reason()).isEqualTo(expected);
            assertThat(rejected.violations()).isNotEmpty();
        });
        assertCounts(0, 0, 0);
        assertThat(receive(CLIENT, "key-1", orderPlaced("o-1"))).isInstanceOf(ReceiveEventResult.Accepted.class);
    }

    private List<ReceiveEventResult> race(int requests, IntFunction<String> bodies) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReceiveEventResult>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
            for (int attempt = 0; attempt < requests; attempt++) {
                String body = bodies.apply(attempt);
                futures.add(executor.submit(() -> {
                    start.await();
                    return receive(CLIENT, "race-key", body);
                }));
            }
            start.countDown();
            List<ReceiveEventResult> results = new ArrayList<>();
            for (Future<ReceiveEventResult> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private static UUID receiptId(ReceiveEventResult result) {
        return switch (result) {
            case ReceiveEventResult.Accepted accepted -> accepted.receipt().id().value();
            case ReceiveEventResult.Replayed replayed -> replayed.receipt().id().value();
            default -> throw new AssertionError("result has no receipt: " + result);
        };
    }

    private ReceiveEventResult receive(ClientId client, String key, String body) {
        return receiveEvent.receive(
                new ReceiveEventCommand(client, new IdempotencyKey(key), CORRELATION, json(body)));
    }

    private void assertCounts(long events, long idempotencyRecords, long outboxMessages) {
        assertThat(count("event")).as("events").isEqualTo(events);
        assertThat(count("idempotency_record")).as("idempotency records").isEqualTo(idempotencyRecords);
        assertThat(count("outbox_message")).as("outbox messages").isEqualTo(outboxMessages);
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    private void insertClient(ClientId client, String name) {
        jdbc.sql("insert into client (id, name, status, created_at) values (:id, :name, 'ACTIVE', now())")
                .param("id", client.value())
                .param("name", name)
                .update();
    }

    private JsonNode json(String body) {
        return reader.read(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String orderPlaced(String orderId) {
        return """
                {
                  "type": "order.placed",
                  "dataversion": "2",
                  "time": "2026-10-20T12:00:00Z",
                  "source": "urn:acme:orders",
                  "subject": "order/%1$s",
                  "data": {
                    "orderId": "%1$s",
                    "customerId": "c-1",
                    "total": {"amount": "15.00", "currency": "BRL"},
                    "items": [{"sku": "sku-1", "quantity": 1, "unitPrice": {"amount": "15.00", "currency": "BRL"}}]
                  }
                }
                """.formatted(orderId);
    }
}
