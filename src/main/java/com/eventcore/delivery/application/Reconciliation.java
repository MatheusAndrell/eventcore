package com.eventcore.delivery.application;

import com.eventcore.ingestion.application.EventDispatchTracking;
import com.eventcore.outbox.application.Outbox;
import com.eventcore.platform.identity.UuidV7Generator;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
@ConditionalOnRole(Role.WORKER)
public class Reconciliation {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);
    private static final String RECONCILED_METRIC = "eventcore.reconciler.repairs";

    private final MeterRegistry meters;
    private final EventDispatchTracking dispatchTracking;
    private final DeliveryStore store;
    private final Outbox outbox;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final ReconcilerProperties properties;

    public Reconciliation(
            EventDispatchTracking dispatchTracking,
            DeliveryStore store,
            Outbox outbox,
            UuidV7Generator ids,
            Clock clock,
            TransactionOperations transactions,
            ReconcilerProperties properties,
            MeterRegistry meters) {
        this.meters = meters;
        this.dispatchTracking = dispatchTracking;
        this.store = store;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.transactions = transactions;
        this.properties = properties;
    }

    public ReconciliationResult reconcile() {
        int events = dispatchTracking.requeueUndispatched(properties.staleAfter(), properties.batchSize());
        Integer exhausted = transactions.execute(status -> store.failExhausted());
        Integer deliveries = transactions.execute(status -> {
            List<UUID> stale = store.claimStale(properties.staleAfter(), properties.batchSize());
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            stale.forEach(deliveryId -> outbox.enqueue(DeliveryRequestedMessage.of(ids.next(), deliveryId, now)));
            return stale.size();
        });
        ReconciliationResult result = new ReconciliationResult(events, deliveries == null ? 0 : deliveries,
                exhausted == null ? 0 : exhausted);
        meters.counter(RECONCILED_METRIC, "kind", "event_requeued").increment(result.requeuedEvents());
        meters.counter(RECONCILED_METRIC, "kind", "delivery_requeued").increment(result.requeuedDeliveries());
        meters.counter(RECONCILED_METRIC, "kind", "delivery_exhausted").increment(result.exhaustedDeliveries());
        if (result.changedAnything()) {
            log.warn("reconciliation requeued {} undispatched events and {} stalled deliveries, "
                    + "and failed {} deliveries that ran out of attempts without a recorded result",
                    result.requeuedEvents(), result.requeuedDeliveries(), result.exhaustedDeliveries());
        }
        return result;
    }
}
