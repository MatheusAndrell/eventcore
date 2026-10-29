package com.eventcore.delivery;

import static com.eventcore.support.StaticHostResolverConfiguration.PRIVATE_HOST;
import static com.eventcore.support.StaticHostResolverConfiguration.REBINDING_HOST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventcore.delivery.application.DeliverWebhook;
import com.eventcore.delivery.application.DispatchEvent;
import com.eventcore.delivery.domain.DeliveryStatus;
import com.eventcore.platform.json.JsonCanonicalizer;
import com.eventcore.platform.json.StrictJsonReader;
import com.eventcore.platform.messaging.MessageHeaders;
import com.eventcore.platform.messaging.MessagingTopology;
import com.eventcore.subscription.application.CreatedSubscription;
import com.eventcore.subscription.domain.SubscribedEventTypes;
import com.eventcore.subscription.domain.SubscriptionChanges;
import com.eventcore.subscription.domain.SubscriptionStatus;
import com.eventcore.subscription.domain.WebhookEndpoint;
import com.eventcore.support.DeliveryIntegrationTest;
import com.eventcore.support.WebhookReceiver.ReceivedWebhook;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class WebhookDeliveryIT extends DeliveryIntegrationTest {

    private static final Duration PARKING_TIMEOUT = Duration.ofSeconds(8);

    @Autowired
    private DispatchEvent dispatchEvent;

    @Autowired
    private DeliverWebhook deliverWebhook;

    @Autowired
    private RabbitTemplate rabbit;

    @Test
    void deliversSignedEventsToEveryMatchingActiveSubscription() throws GeneralSecurityException {
        CreatedSubscription payments = subscribe("/payments", "payment.captured");
        CreatedSubscription everything = subscribe("/everything", "payment.captured", "order.placed");
        subscribe("/orders", "order.placed");
        CreatedSubscription paused = subscribe("/paused", "payment.captured");
        subscriptions.change(client, paused.subscription().id(), new SubscriptionChanges(Optional.empty(),
                Optional.empty(), Optional.of(SubscriptionStatus.PAUSED)));
        Instant before = Instant.now();

        UUID eventId = receivePayment("payment-1");

        List<Map<String, Object>> deliveries = awaitDeliveries(eventId, 2);
        assertThat(deliveries).extracting(row -> row.get("status")).containsOnly("SUCCEEDED");
        assertThat(deliveries).extracting(row -> row.get("attempts")).containsOnly(1);
        assertThat(receiver.received()).extracting(ReceivedWebhook::path)
                .containsExactlyInAnyOrder("/payments", "/everything");
        assertThat(jdbc.sql("select status from event where id = :id").param("id", eventId).query(String.class)
                .single()).isEqualTo("DISPATCHED");

        ReceivedWebhook webhook = receiver.received("/payments").getFirst();
        assertThat(webhook.method()).isEqualTo("POST");
        assertThat(webhook.header("webhook-id")).isEqualTo(eventId.toString());
        assertThat(Instant.ofEpochSecond(Long.parseLong(webhook.header("webhook-timestamp"))))
                .isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(webhook.header("content-type")).startsWith("application/json");
        assertThat(webhook.header("user-agent")).isEqualTo("EventCore-Webhooks/1");
        assertThat(webhook.header("eventcore-delivery-id")).isEqualTo(deliveryFor(eventId, payments).toString());
        assertThat(signatures(webhook)).containsExactly(expectedSignature(webhook, payments.secret().value()));
        ReceivedWebhook toEverything = receiver.received("/everything").getFirst();
        assertThat(signatures(toEverything))
                .containsExactly(expectedSignature(toEverything, everything.secret().value()));

        JsonNode body = JSON.readTree(webhook.bodyText());
        assertThat(body.get("id").stringValue()).isEqualTo(eventId.toString());
        assertThat(body.get("type").stringValue()).isEqualTo("payment.captured");
        assertThat(body.get("dataversion").stringValue()).isEqualTo("1");
        assertThat(body.get("source").stringValue()).isEqualTo("urn:acme:payments");
        assertThat(body.get("time").stringValue()).isEqualTo("2026-10-20T12:00:00Z");
        assertThat(body.get("data")).isEqualTo(JSON.readTree(
                "{\"paymentId\":\"p-1\",\"orderId\":\"o-1\",\"amount\":\"15.00\",\"currency\":\"BRL\"}"));
        assertThat(webhook.bodyText()).isEqualTo(new String(new JsonCanonicalizer()
                .canonicalize(new StrictJsonReader().read(webhook.body())), StandardCharsets.UTF_8));
        assertThat(jdbc.sql("""
                        select count(*) from delivery_attempt a join delivery d on d.id = a.delivery_id
                         where d.event_id = :eventId and a.succeeded and a.http_status = 204 and a.attempt_number = 1
                        """).param("eventId", eventId).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void signsWithBothSecretsDuringRotation() throws GeneralSecurityException {
        CreatedSubscription subscription = subscribe("/rotating", "payment.captured");
        String rotated = subscriptions.rotateSecret(client, subscription.subscription().id()).orElseThrow()
                .secret().value();

        awaitDeliveries(receivePayment("payment-rotation"), 1);

        ReceivedWebhook webhook = receiver.received("/rotating").getFirst();
        assertThat(signatures(webhook)).containsExactlyInAnyOrder(
                expectedSignature(webhook, rotated), expectedSignature(webhook, subscription.secret().value()));
    }

    @Test
    void retriesRejectedAndRedirectedDeliveriesUntilTheAttemptsRunOut() {
        subscribe("/rejecting", "payment.captured");
        subscribe("/redirecting", "payment.captured");
        receiver.reply("/rejecting", 500);
        receiver.redirect("/redirecting", receiver.url("/elsewhere"));

        UUID eventId = receivePayment("payment-failing");

        List<Map<String, Object>> deliveries = awaitDeliveries(eventId, 2);
        assertThat(deliveries).extracting(row -> row.get("status")).containsOnly("FAILED");
        assertThat(deliveries).extracting(row -> row.get("attempts")).containsOnly(3);
        assertThat(deliveries).extracting(row -> row.get("last_error"))
                .containsExactlyInAnyOrder("HTTP 500", "HTTP 302");
        assertThat(receiver.received("/rejecting")).hasSize(3);
        assertThat(receiver.received("/redirecting")).hasSize(3);
        assertThat(receiver.received("/elsewhere")).isEmpty();
        assertThat(deliveries).allSatisfy(row -> assertThat(attemptStatuses((UUID) row.get("id")))
                .hasSize(3).containsOnly(row.get("last_error").equals("HTTP 500") ? 500 : 302));
    }

    @Test
    void succeedsWhenTheReceiverRecoversBeforeTheAttemptsRunOut() {
        subscribe("/flaky", "payment.captured");
        receiver.replyInOrder("/flaky", 503, 500);

        UUID eventId = receivePayment("payment-flaky");

        Map<String, Object> delivery = awaitDeliveries(eventId, 1).getFirst();
        assertThat(delivery.get("status")).isEqualTo("SUCCEEDED");
        assertThat(delivery.get("attempts")).isEqualTo(3);
        assertThat(attemptStatuses((UUID) delivery.get("id"))).containsExactly(503, 500, 204);
        assertThat(receiver.received("/flaky")).extracting(webhook -> webhook.header("webhook-id")).containsOnly(
                eventId.toString());
    }

    @Test
    void abandonsReceiversThatDoNotAnswerInTime() {
        subscribe("/slow", "payment.captured");
        receiver.replySlowly("/slow", Duration.ofSeconds(5));

        UUID eventId = receivePayment("payment-slow");

        Map<String, Object> delivery = awaitDeliveries(eventId, 1).getFirst();
        assertThat(delivery.get("status")).isEqualTo("FAILED");
        assertThat((String) delivery.get("last_error")).isNotBlank().doesNotStartWith("HTTP");
        assertThat(attemptDurations(eventId)).hasSize(3).allSatisfy(duration -> assertThat(duration)
                .isLessThan(4_000L));
    }

    @Test
    void abortsReceiversThatTrickleTheirResponsePastTheDeadline() {
        subscriptions.create(client, WebhookEndpoint.parse(receiver.tricklingUrl("/trickle")),
                SubscribedEventTypes.of(List.of("payment.captured")));

        UUID eventId = receivePayment("payment-trickle");

        Map<String, Object> delivery = awaitDeliveries(eventId, 1).getFirst();
        assertThat(receiver.trickledRequests()).isEqualTo(3);
        assertThat(delivery.get("status")).isEqualTo("FAILED");
        assertThat(delivery.get("last_error")).isEqualTo("request exceeded the 2000 ms deadline");
        assertThat(attemptDurations(eventId)).hasSize(3).allSatisfy(duration -> assertThat(duration)
                .isBetween(2_000L, 4_000L));
    }

    @Test
    void neverConnectsToNonPublicAddressesEvenWhenTheEndpointChangedAfterRegistration() {
        CreatedSubscription internal = subscribe("/internal", "payment.captured");
        CreatedSubscription rebinding = subscribe("/rebinding", "payment.captured");
        moveEndpoint(internal, "https://" + PRIVATE_HOST + ":" + receiver.port() + "/internal");
        moveEndpoint(rebinding, "https://" + REBINDING_HOST + ":" + receiver.port() + "/rebinding");

        UUID eventId = receivePayment("payment-private");

        List<Map<String, Object>> deliveries = awaitDeliveries(eventId, 2);
        assertThat(deliveries).extracting(row -> row.get("status")).containsOnly("FAILED");
        assertThat(deliveries).extracting(row -> row.get("last_error"))
                .containsOnly("destination not allowed: host resolves to a non-public address");
        assertThat(receiver.received()).isEmpty();
    }

    @Test
    void processesRepeatedMessagesOnlyOnce() {
        subscribe("/once", "payment.captured");
        UUID eventId = receivePayment("payment-once");
        UUID deliveryId = (UUID) awaitDeliveries(eventId, 1).getFirst().get("id");

        int redispatched = dispatchEvent.dispatch(eventId);
        Optional<DeliveryStatus> redelivered = deliverWebhook.deliver(deliveryId);

        assertThat(redispatched).isZero();
        assertThat(redelivered).isEmpty();
        assertThat(receiver.received("/once")).hasSize(1);
        assertThat(jdbc.sql("select count(*) from delivery where event_id = :eventId").param("eventId", eventId)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void cancelsDeliveriesWhoseSubscriptionWasPausedOrDeleted() {
        CreatedSubscription paused = subscribe("/paused-later", "order.placed");
        CreatedSubscription deleted = subscribe("/deleted-later", "order.placed");
        UUID eventId = receivePayment("payment-cancelled");
        awaitDeliveries(eventId, 0);
        UUID pausedDelivery = insertPendingDelivery(eventId, paused);
        UUID deletedDelivery = insertPendingDelivery(eventId, deleted);
        subscriptions.change(client, paused.subscription().id(), new SubscriptionChanges(Optional.empty(),
                Optional.empty(), Optional.of(SubscriptionStatus.PAUSED)));
        subscriptions.delete(client, deleted.subscription().id());

        assertThat(deliverWebhook.deliver(pausedDelivery)).contains(DeliveryStatus.CANCELLED);
        assertThat(deliverWebhook.deliver(deletedDelivery)).contains(DeliveryStatus.CANCELLED);
        assertThat(jdbc.sql("select last_error from delivery where event_id = :eventId order by last_error")
                .param("eventId", eventId).query(String.class).list())
                .containsExactly("subscription is paused", "subscription was deleted");
        assertThat(receiver.received()).isEmpty();
    }

    @Test
    void parksMessagesThatCannotBeProcessedWithoutRetryingThem() {
        String malformed = UUID.randomUUID().toString();
        String unknownEvent = UUID.randomUUID().toString();
        String wrongVersion = UUID.randomUUID().toString();

        publish(malformed, "event.accepted", 1, "not json");
        publish(unknownEvent, "event.accepted", 1, "{\"eventId\":\"" + UUID.randomUUID() + "\"}");
        publish(wrongVersion, "delivery.requested", 2, "{\"deliveryId\":\"" + UUID.randomUUID() + "\"}");

        List<String> parked = new ArrayList<>();
        await().atMost(PARKING_TIMEOUT).until(() -> {
            Message message = rabbit.receive(MessagingTopology.PARKING_QUEUE, 200);
            if (message != null) {
                parked.add(message.getMessageProperties().getMessageId());
            }
            return parked.containsAll(List.of(malformed, unknownEvent, wrongVersion));
        });
    }

    private void publish(String messageId, String type, int version, String body) {
        String routingKey = type.equals("event.accepted")
                ? MessagingTopology.EVENT_ACCEPTED_ROUTING_KEY
                : MessagingTopology.DELIVERY_REQUESTED_ROUTING_KEY;
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, routingKey, MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setMessageId(messageId)
                .setType(type)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader(MessageHeaders.MESSAGE_VERSION, version)
                .build());
    }

    private void moveEndpoint(CreatedSubscription subscription, String url) {
        jdbc.sql("update subscription set endpoint_url = :url where id = :id")
                .param("url", url)
                .param("id", subscription.subscription().id())
                .update();
    }

    private UUID insertPendingDelivery(UUID eventId, CreatedSubscription subscription) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into delivery (
                            id, event_id, subscription_id, client_id, status, attempts, max_attempts, next_attempt_at,
                            created_at, updated_at)
                        values (:id, :eventId, :subscriptionId, :clientId, 'PENDING', 0, 3, now(), now(), now())
                        """)
                .param("id", id)
                .param("eventId", eventId)
                .param("subscriptionId", subscription.subscription().id())
                .param("clientId", client.value())
                .update();
        return id;
    }

    private List<Long> attemptDurations(UUID eventId) {
        return jdbc.sql("""
                        select a.duration_ms from delivery_attempt a join delivery d on d.id = a.delivery_id
                         where d.event_id = :eventId order by a.attempt_number
                        """).param("eventId", eventId).query(Long.class).list();
    }

    private UUID deliveryFor(UUID eventId, CreatedSubscription subscription) {
        return jdbc.sql("select id from delivery where event_id = :eventId and subscription_id = :subscriptionId")
                .param("eventId", eventId)
                .param("subscriptionId", subscription.subscription().id())
                .query(UUID.class)
                .single();
    }

    private static List<String> signatures(ReceivedWebhook webhook) {
        return Arrays.asList(webhook.header("webhook-signature").split(" "));
    }

    private static String expectedSignature(ReceivedWebhook webhook, String secret) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.substring("whsec_".length())), "HmacSHA256"));
        mac.update((webhook.header("webhook-id") + "." + webhook.header("webhook-timestamp") + ".")
                .getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(webhook.body()));
    }
}
