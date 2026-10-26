package com.eventcore.subscription.domain;

import java.util.Objects;
import java.util.Optional;

public record SubscriptionChanges(
        Optional<WebhookEndpoint> endpoint,
        Optional<SubscribedEventTypes> eventTypes,
        Optional<SubscriptionStatus> status) {

    public SubscriptionChanges {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(eventTypes, "eventTypes");
        Objects.requireNonNull(status, "status");
        if (endpoint.isEmpty() && eventTypes.isEmpty() && status.isEmpty()) {
            throw new InvalidSubscriptionException("request", "must change url, eventTypes or status");
        }
    }
}
