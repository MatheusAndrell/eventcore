package com.eventcore.subscription.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SubscriptionSecret(UUID id, UUID subscriptionId, EncryptedSecret secret, Instant createdAt) {

    public SubscriptionSecret {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
