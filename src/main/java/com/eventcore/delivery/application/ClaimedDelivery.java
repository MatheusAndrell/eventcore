package com.eventcore.delivery.application;

import java.util.Objects;
import java.util.UUID;

public record ClaimedDelivery(UUID id, UUID eventId, UUID subscriptionId, UUID claimToken, int attemptNumber) {

    public ClaimedDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(claimToken, "claimToken");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive: " + attemptNumber);
        }
    }
}
