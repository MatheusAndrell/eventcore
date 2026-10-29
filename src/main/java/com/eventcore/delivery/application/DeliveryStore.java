package com.eventcore.delivery.application;

import com.eventcore.delivery.domain.DeliveryStatus;
import com.eventcore.platform.identity.ClientId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryStore {

    boolean create(
            UUID deliveryId, UUID eventId, UUID subscriptionId, ClientId clientId, int maxAttempts, Instant createdAt);

    Optional<ClaimedDelivery> claim(UUID deliveryId, UUID claimToken, Duration lease);

    boolean cancel(ClaimedDelivery delivery, String reason, Instant now);

    boolean complete(ClaimedDelivery delivery, DeliveryAttempt attempt, DeliveryStatus status, Instant now);

    boolean scheduleRetry(ClaimedDelivery delivery, DeliveryAttempt attempt, Duration delay, Instant now);

    int failExhausted();

    List<UUID> claimStale(Duration staleAfter, int limit);

    boolean reopen(ClientId clientId, UUID deliveryId, int additionalAttempts);

    List<DeliverySummary> listForEvent(ClientId clientId, UUID eventId);

    Optional<DeliverySummary> find(ClientId clientId, UUID deliveryId);

    List<DeliveryAttempt> attempts(UUID deliveryId);

    long countPending();

    long countDue();

    int deleteCompletedBefore(Duration age, int limit);
}
