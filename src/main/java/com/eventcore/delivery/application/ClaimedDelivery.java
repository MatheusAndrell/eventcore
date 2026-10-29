package com.eventcore.delivery.application;

import java.util.Objects;
import java.util.UUID;

public record ClaimedDelivery(
        UUID id, UUID eventId, UUID subscriptionId, UUID claimToken, int attemptNumber, int maxAttempts) {

    public ClaimedDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(claimToken, "claimToken");
        if (attemptNumber < 1 || attemptNumber > maxAttempts) {
            throw new IllegalArgumentException(
                    "attemptNumber must be between 1 and " + maxAttempts + ": " + attemptNumber);
        }
    }

    public boolean lastAttempt() {
        return attemptNumber >= maxAttempts;
    }
}
