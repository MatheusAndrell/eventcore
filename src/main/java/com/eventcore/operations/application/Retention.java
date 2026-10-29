package com.eventcore.operations.application;

import com.eventcore.delivery.application.DeliveryStore;
import com.eventcore.ingestion.application.EventStore;
import com.eventcore.outbox.application.OutboxStore;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.IntUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnRole(Role.WORKER)
public class Retention {

    private static final Logger log = LoggerFactory.getLogger(Retention.class);
    private static final String DELETED_METRIC = "eventcore.retention.deleted";

    private final EventStore events;
    private final DeliveryStore deliveries;
    private final OutboxStore outbox;
    private final RetentionProperties properties;
    private final MeterRegistry meters;

    public Retention(
            EventStore events,
            DeliveryStore deliveries,
            OutboxStore outbox,
            RetentionProperties properties,
            MeterRegistry meters) {
        this.events = events;
        this.deliveries = deliveries;
        this.outbox = outbox;
        this.properties = properties;
        this.meters = meters;
    }

    public RetentionResult purge() {
        RetentionResult result = new RetentionResult(
                inBatches("idempotency_record", events::deleteExpiredIdempotencyRecords),
                inBatches("delivery", limit -> deliveries.deleteCompletedBefore(properties.events(), limit)),
                inBatches("event",
                        limit -> events.deleteDispatchedEventsWithoutDeliveries(properties.events(), limit)),
                inBatches("outbox_message",
                        limit -> outbox.deletePublishedBefore(properties.publishedMessages(), limit)));
        if (result.deletedAnything()) {
            log.info("retention removed {} idempotency records, {} deliveries, {} events and {} outbox messages",
                    result.idempotencyRecords(), result.deliveries(), result.events(), result.outboxMessages());
        }
        return result;
    }

    private int inBatches(String kind, IntUnaryOperator deleteBatch) {
        int total = 0;
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            int deleted = deleteBatch.applyAsInt(properties.batchSize());
            total += deleted;
            if (deleted < properties.batchSize()) {
                break;
            }
        }
        meters.counter(DELETED_METRIC, "kind", kind).increment(total);
        return total;
    }
}
