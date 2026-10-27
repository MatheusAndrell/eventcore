package com.eventcore.delivery.application;

import com.eventcore.delivery.domain.DeliveryStatus;
import com.eventcore.platform.identity.ClientId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryStore {

    boolean create(UUID deliveryId, UUID eventId, UUID subscriptionId, ClientId clientId, Instant createdAt);

    Optional<ClaimedDelivery> claim(UUID deliveryId, UUID claimToken, Duration lease);

    boolean cancel(ClaimedDelivery delivery, String reason, Instant now);

    boolean complete(ClaimedDelivery delivery, DeliveryAttempt attempt, DeliveryStatus status, Instant now);
}
