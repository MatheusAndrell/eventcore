package com.eventcore.delivery.application;

import com.eventcore.ingestion.application.EventDispatchTracking;
import com.eventcore.ingestion.application.EventQueries;
import com.eventcore.ingestion.application.EventView;
import com.eventcore.outbox.application.Outbox;
import com.eventcore.platform.identity.CorrelationId;
import com.eventcore.platform.identity.UuidV7Generator;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import com.eventcore.subscription.application.SubscriptionDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
@ConditionalOnRole(Role.WORKER)
public class DispatchEvent {

    private final EventQueries events;
    private final SubscriptionDirectory subscriptions;
    private final DeliveryStore store;
    private final Outbox outbox;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final EventDispatchTracking dispatchTracking;
    private final DeliveryProperties properties;

    public DispatchEvent(
            EventQueries events,
            SubscriptionDirectory subscriptions,
            DeliveryStore store,
            Outbox outbox,
            UuidV7Generator ids,
            Clock clock,
            TransactionOperations transactions,
            EventDispatchTracking dispatchTracking,
            DeliveryProperties properties) {
        this.dispatchTracking = dispatchTracking;
        this.properties = properties;
        this.events = events;
        this.subscriptions = subscriptions;
        this.store = store;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.transactions = transactions;
    }

    public int dispatch(UUID eventId) {
        EventView event = events.view(eventId).orElseThrow(() -> new UnknownEventException(eventId));
        try (var _ = MDC.putCloseable(CorrelationId.MDC_KEY, event.correlationId().value())) {
            return dispatch(event);
        }
    }

    private int dispatch(EventView event) {
        List<UUID> targets = subscriptions.activeSubscriptions(event.clientId(), event.type());
        Integer created = transactions.execute(status -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            int count = 0;
            for (UUID subscriptionId : targets) {
                UUID deliveryId = ids.next();
                if (store.create(deliveryId, event.id(), subscriptionId, event.clientId(), properties.maxAttempts(),
                        now)) {
                    outbox.enqueue(DeliveryRequestedMessage.of(ids.next(), deliveryId, now));
                    count++;
                }
            }
            dispatchTracking.markDispatched(event.id());
            return count;
        });
        return created == null ? 0 : created;
    }
}
