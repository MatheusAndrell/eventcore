package com.eventcore.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventcore.access.domain.Scope;
import com.eventcore.platform.messaging.MessageHeaders;
import com.eventcore.platform.messaging.MessagingTopology;
import com.eventcore.subscription.application.CreatedSubscription;
import com.eventcore.support.DeliveryIntegrationTest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = {"eventcore.reconciler.interval=200ms", "eventcore.reconciler.stale-after=1s"})
class RecoveryIT extends DeliveryIntegrationTest {

    @Autowired
    private RabbitTemplate rabbit;

    @Test
    void resumesDeliveriesLeftBehindByACrashedWorker() {
        CreatedSubscription subscription = subscribe("/recovered", "order.placed");
        UUID eventId = receivePayment("payment-crash");
        awaitDispatched(eventId);
        UUID deliveryId = insertDelivery(eventId, subscription, 1, 3, "now() - interval '5 seconds'");

        await().atMost(DELIVERY_TIMEOUT).until(() -> "SUCCEEDED".equals(status(deliveryId)));

        assertThat(receiver.received("/recovered")).hasSize(1);
        assertThat(attemptStatuses(deliveryId)).containsExactly(204);
        assertThat(jdbc.sql("select attempts from delivery where id = :id").param("id", deliveryId)
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void failsDeliveriesThatRanOutOfAttemptsWithoutARecordedResult() {
        CreatedSubscription subscription = subscribe("/exhausted", "order.placed");
        UUID eventId = receivePayment("payment-exhausted");
        awaitDispatched(eventId);
        UUID deliveryId = insertDelivery(eventId, subscription, 3, 3, "now() - interval '5 seconds'");

        await().atMost(DELIVERY_TIMEOUT).until(() -> "FAILED".equals(status(deliveryId)));

        assertThat(jdbc.sql("select last_error from delivery where id = :id").param("id", deliveryId)
                .query(String.class).single()).isEqualTo("attempts exhausted without a recorded result");
        assertThat(receiver.received()).isEmpty();
    }

    @Test
    void redeliversWhenTheOutcomeOfASuccessfulAttemptWasLost() {
        subscribe("/duplicated", "payment.captured");
        UUID eventId = receivePayment("payment-duplicated");
        UUID deliveryId = (UUID) awaitDeliveries(eventId, 1).getFirst().get("id");
        jdbc.sql("""
                        update delivery set status = 'PENDING', completed_at = null,
                                            next_attempt_at = now() - interval '5 seconds'
                         where id = :id
                        """).param("id", deliveryId).update();

        await().atMost(DELIVERY_TIMEOUT).until(() -> receiver.received("/duplicated").size() == 2
                && "SUCCEEDED".equals(status(deliveryId)));

        assertThat(receiver.received("/duplicated")).extracting(webhook -> webhook.header("webhook-id"))
                .containsExactly(eventId.toString(), eventId.toString());
    }

    @Test
    void redispatchesEventsWhoseDispatchWasLost() {
        subscribe("/early", "payment.captured");
        UUID eventId = receivePayment("payment-redispatch");
        awaitDeliveries(eventId, 1);
        subscribe("/late", "payment.captured");
        jdbc.sql("""
                        update event set status = 'RECEIVED', dispatched_at = null,
                                         received_at = received_at - interval '1 minute'
                         where id = :id
                        """).param("id", eventId).update();

        await().atMost(DELIVERY_TIMEOUT).until(() -> receiver.received("/late").size() == 1);
        awaitDispatched(eventId);

        assertThat(receiver.received("/early")).hasSize(1);
        assertThat(deliveries(eventId)).hasSize(2).extracting(row -> row.get("status")).containsOnly("SUCCEEDED");
    }

    @Test
    void replaysParkedMessagesOnceTheyCanBeProcessed() {
        subscribe("/replayed", "payment.captured");
        String admin = issueKey(Scope.ADMIN);
        UUID eventId = UUID.randomUUID();
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.EVENT_ACCEPTED_ROUTING_KEY, MessageBuilder
                .withBody(("{\"eventId\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8))
                .setMessageId(UUID.randomUUID().toString())
                .setType("event.accepted")
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader(MessageHeaders.MESSAGE_VERSION, 1)
                .build());
        await().atMost(DELIVERY_TIMEOUT).until(() -> json(get("/admin/parking", admin)).get("messages").longValue() > 0);
        jdbc.sql("""
                        insert into event (
                            id, client_id, type, data_version, source, occurred_at, received_at, data, status,
                            correlation_id, dispatched_at)
                        values (:id, :clientId, 'payment.captured', 1, 'urn:acme:payments', now(), now(),
                                '{"paymentId":"p-9"}', 'DISPATCHED', 'replay-it', now())
                        """)
                .param("id", eventId)
                .param("clientId", client.value())
                .update();

        HttpResponse<String> replay = post("/admin/parking/replay", admin, "{\"limit\": 100}");

        assertThat(replay.statusCode()).isEqualTo(200);
        JsonNode result = json(replay);
        assertThat(result.get("replayed").intValue()).isPositive();
        assertThat(result.has("stoppedBecause")).isFalse();
        await().atMost(DELIVERY_TIMEOUT).until(() -> receiver.received("/replayed").size() == 1);
        assertThat(receiver.received("/replayed").getFirst().header("webhook-id")).isEqualTo(eventId.toString());
        assertThat(post("/admin/parking/replay", admin, "{\"limit\": 0}").statusCode()).isEqualTo(400);
        assertThat(get("/admin/parking", issueKey(Scope.EVENTS_READ)).statusCode()).isEqualTo(403);
    }

    private UUID insertDelivery(
            UUID eventId, CreatedSubscription subscription, int attempts, int maxAttempts, String claimedUntil) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into delivery (
                            id, event_id, subscription_id, client_id, status, attempts, max_attempts, claim_token,
                            claimed_until, next_attempt_at, created_at, updated_at)
                        values (:id, :eventId, :subscriptionId, :clientId, 'PENDING', :attempts, :maxAttempts,
                                gen_random_uuid(), %s, now() - interval '10 seconds', now(), now())
                        """.formatted(claimedUntil))
                .param("id", id)
                .param("eventId", eventId)
                .param("subscriptionId", subscription.subscription().id())
                .param("clientId", client.value())
                .param("attempts", attempts)
                .param("maxAttempts", maxAttempts)
                .update();
        return id;
    }

    private void awaitDispatched(UUID eventId) {
        await().atMost(DELIVERY_TIMEOUT).until(() -> "DISPATCHED".equals(jdbc.sql(
                "select status from event where id = :id").param("id", eventId).query(String.class).single()));
    }

    private String status(UUID deliveryId) {
        return jdbc.sql("select status from delivery where id = :id").param("id", deliveryId).query(String.class)
                .single();
    }
}
