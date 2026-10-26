package com.eventcore.subscription.application;

import com.eventcore.subscription.domain.WebhookSecret;
import java.time.Instant;
import java.util.Objects;

public record RotatedSecret(WebhookSecret secret, Instant previousSecretExpiresAt) {

    public RotatedSecret {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(previousSecretExpiresAt, "previousSecretExpiresAt");
    }
}
