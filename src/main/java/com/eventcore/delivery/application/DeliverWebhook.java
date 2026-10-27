package com.eventcore.delivery.application;

import com.eventcore.delivery.domain.DeliveryStatus;
import com.eventcore.delivery.domain.StandardWebhookSignature;
import com.eventcore.ingestion.application.EventQueries;
import com.eventcore.ingestion.application.EventView;
import com.eventcore.platform.identity.UuidV7Generator;
import com.eventcore.platform.json.JsonCanonicalizer;
import com.eventcore.platform.json.StrictJsonReader;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import com.eventcore.subscription.application.DeliveryTarget;
import com.eventcore.subscription.application.SubscriptionDirectory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
@ConditionalOnRole(Role.WORKER)
public class DeliverWebhook {

    public static final String DELIVERY_ID_HEADER = "eventcore-delivery-id";
    private static final Logger log = LoggerFactory.getLogger(DeliverWebhook.class);

    private final DeliveryStore store;
    private final SubscriptionDirectory subscriptions;
    private final EventQueries events;
    private final WebhookTransport transport;
    private final StrictJsonReader jsonReader;
    private final JsonCanonicalizer canonicalizer;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final DeliveryProperties properties;

    public DeliverWebhook(
            DeliveryStore store,
            SubscriptionDirectory subscriptions,
            EventQueries events,
            WebhookTransport transport,
            StrictJsonReader jsonReader,
            JsonCanonicalizer canonicalizer,
            UuidV7Generator ids,
            Clock clock,
            TransactionOperations transactions,
            DeliveryProperties properties) {
        this.store = store;
        this.subscriptions = subscriptions;
        this.events = events;
        this.transport = transport;
        this.jsonReader = jsonReader;
        this.canonicalizer = canonicalizer;
        this.ids = ids;
        this.clock = clock;
        this.transactions = transactions;
        this.properties = properties;
    }

    public Optional<DeliveryStatus> deliver(UUID deliveryId) {
        Optional<ClaimedDelivery> claimed = store.claim(deliveryId, UUID.randomUUID(), properties.lease());
        if (claimed.isEmpty()) {
            log.debug("delivery {} is not pending or is claimed by another worker", deliveryId);
            return Optional.empty();
        }
        ClaimedDelivery delivery = claimed.get();
        Optional<DeliveryTarget> target = subscriptions.deliveryTarget(delivery.subscriptionId());
        if (target.isEmpty() || !target.get().active()) {
            String reason = target.isEmpty() ? "subscription was deleted" : "subscription is paused";
            store.cancel(delivery, reason, now());
            log.info("delivery {} cancelled: {}", delivery.id(), reason);
            return Optional.of(DeliveryStatus.CANCELLED);
        }
        EventView event = events.view(delivery.eventId()).orElseThrow(() -> new IllegalStateException(
                "delivery " + delivery.id() + " references missing event " + delivery.eventId()));
        return Optional.of(send(delivery, target.get(), event));
    }

    private DeliveryStatus send(ClaimedDelivery delivery, DeliveryTarget target, EventView event) {
        byte[] body = payload(event);
        Instant startedAt = now();
        String webhookId = event.id().toString();
        long timestamp = startedAt.getEpochSecond();
        WebhookRequest request = new WebhookRequest(target.endpoint(), Map.of(
                StandardWebhookSignature.ID_HEADER, webhookId,
                StandardWebhookSignature.TIMESTAMP_HEADER, Long.toString(timestamp),
                StandardWebhookSignature.SIGNATURE_HEADER,
                StandardWebhookSignature.sign(webhookId, timestamp, body, target.signingKeys()),
                DELIVERY_ID_HEADER, delivery.id().toString()), body);
        long started = System.nanoTime();
        TransportResult result = transport.send(request);
        DeliveryAttempt attempt = DeliveryAttempt.of(ids.next(), delivery.attemptNumber(), startedAt,
                Duration.ofNanos(System.nanoTime() - started), result);
        DeliveryStatus status = attempt.succeeded() ? DeliveryStatus.SUCCEEDED : DeliveryStatus.FAILED;
        Boolean recorded = transactions.execute(transaction -> store.complete(delivery, attempt, status, now()));
        if (!Boolean.TRUE.equals(recorded)) {
            log.warn("delivery {} attempt {} finished after its lease was lost; the result was not recorded",
                    delivery.id(), delivery.attemptNumber());
        } else if (status == DeliveryStatus.FAILED) {
            log.warn("delivery {} to subscription {} failed on attempt {}: {}", delivery.id(),
                    delivery.subscriptionId(), delivery.attemptNumber(), attempt.error().orElse("unknown error"));
        }
        return status;
    }

    private byte[] payload(EventView event) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("id", event.id().toString())
                .put("type", event.type())
                .put("dataversion", Integer.toString(event.dataVersion()))
                .put("source", event.source())
                .put("time", event.occurredAt().toString());
        event.subject().ifPresent(subject -> payload.put("subject", subject));
        payload.set("data", jsonReader.read(event.data().getBytes(StandardCharsets.UTF_8)));
        return canonicalizer.canonicalize(payload);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
