package com.eventcore.delivery.application;

import java.util.List;
import java.util.Objects;

public record DeliveryDetails(DeliverySummary delivery, List<DeliveryAttempt> attempts) {

    public DeliveryDetails {
        Objects.requireNonNull(delivery, "delivery");
        attempts = List.copyOf(attempts);
    }
}
