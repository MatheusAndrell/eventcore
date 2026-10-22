package com.eventcore.outbox.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int MAX_ERROR_LENGTH = 512;

    private final OutboxStore store;
    private final MessagePublisher publisher;
    private final OutboxProperties properties;

    public OutboxPublisher(OutboxStore store, MessagePublisher publisher, OutboxProperties properties) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
    }

    public BatchResult publishBatch() {
        UUID claimToken = UUID.randomUUID();
        List<PendingMessage> claimed = store.claim(claimToken, properties.lease(), properties.batchSize());
        if (claimed.isEmpty()) {
            return BatchResult.empty();
        }
        Map<UUID, PublishOutcome> outcomes = publisher.publish(claimed, properties.confirmTimeout());
        int published = 0;
        int rescheduled = 0;
        boolean brokerUnavailable = false;
        for (PendingMessage message : claimed) {
            PublishOutcome outcome = outcomes.getOrDefault(message.id(), new PublishOutcome.TimedOut());
            if (outcome instanceof PublishOutcome.Confirmed) {
                published += markPublished(message, claimToken) ? 1 : 0;
            } else {
                reschedule(message, claimToken, outcome);
                rescheduled++;
                brokerUnavailable |= outcome instanceof PublishOutcome.Failed;
            }
        }
        return new BatchResult(claimed.size(), published, rescheduled, brokerUnavailable);
    }

    private boolean markPublished(PendingMessage message, UUID claimToken) {
        if (store.markPublished(message.id(), claimToken)) {
            return true;
        }
        log.warn("outbox message {} was confirmed after its lease moved to another publisher; "
                + "it may be delivered more than once", message.id());
        return false;
    }

    private void reschedule(PendingMessage message, UUID claimToken, PublishOutcome outcome) {
        String error = describe(outcome);
        if (outcome instanceof PublishOutcome.Returned) {
            log.error("outbox message {} ({}) is unroutable on exchange '{}' with routing key '{}': {}",
                    message.id(), message.messageType(), message.exchange(), message.routingKey(), error);
        } else {
            log.warn("outbox message {} ({}) was not confirmed: {}", message.id(), message.messageType(), error);
        }
        store.reschedule(message.id(), claimToken, properties.retryDelay(message.publishAttempts()),
                truncate(error));
    }

    private static String describe(PublishOutcome outcome) {
        return switch (outcome) {
            case PublishOutcome.Confirmed _ -> "confirmed";
            case PublishOutcome.Nacked nacked -> "nacked: " + nacked.reason();
            case PublishOutcome.Returned returned -> "returned " + returned.replyCode() + " " + returned.replyText();
            case PublishOutcome.TimedOut _ -> "confirmation timed out";
            case PublishOutcome.Failed failed -> "publish failed: " + failed.reason();
        };
    }

    private static String truncate(String error) {
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
