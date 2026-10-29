package com.eventcore.delivery.application;

import com.eventcore.delivery.domain.DeliveryStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record DeliverySummary(
        UUID id,
        UUID eventId,
        UUID subscriptionId,
        DeliveryStatus status,
        int attempts,
        int maxAttempts,
        Optional<Instant> nextAttemptAt,
        Optional<String> lastError,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> completedAt) {

    public DeliverySummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(lastError, "lastError");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
