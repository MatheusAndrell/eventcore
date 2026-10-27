package com.eventcore.subscription.application;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DeliveryTarget(UUID subscriptionId, URI endpoint, boolean active, List<byte[]> signingKeys) {

    public DeliveryTarget {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(endpoint, "endpoint");
        signingKeys = signingKeys.stream().map(byte[]::clone).toList();
    }

    @Override
    public List<byte[]> signingKeys() {
        return signingKeys.stream().map(byte[]::clone).toList();
    }

    @Override
    public String toString() {
        return "DeliveryTarget[subscriptionId=" + subscriptionId + ", endpoint=" + endpoint + ", active=" + active
                + ", signingKeys=<redacted>]";
    }
}
