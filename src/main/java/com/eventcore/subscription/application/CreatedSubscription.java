package com.eventcore.subscription.application;

import com.eventcore.subscription.domain.Subscription;
import com.eventcore.subscription.domain.WebhookSecret;
import java.util.Objects;

public record CreatedSubscription(Subscription subscription, WebhookSecret secret) {

    public CreatedSubscription {
        Objects.requireNonNull(subscription, "subscription");
        Objects.requireNonNull(secret, "secret");
    }
}
