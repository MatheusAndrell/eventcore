package com.eventcore.subscription.domain;

import com.eventcore.platform.identity.ClientId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Subscription(
        UUID id,
        ClientId clientId,
        WebhookEndpoint endpoint,
        SubscribedEventTypes eventTypes,
        SubscriptionStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public Subscription {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(eventTypes, "eventTypes");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }

    public static Subscription create(
            UUID id, ClientId clientId, WebhookEndpoint endpoint, SubscribedEventTypes eventTypes, Instant now) {
        return new Subscription(id, clientId, endpoint, eventTypes, SubscriptionStatus.ACTIVE, now, now);
    }

    public Subscription apply(SubscriptionChanges changes, Instant now) {
        return new Subscription(id, clientId, changes.endpoint().orElse(endpoint),
                changes.eventTypes().orElse(eventTypes), changes.status().orElse(status), createdAt,
                now.isBefore(createdAt) ? createdAt : now);
    }
}
