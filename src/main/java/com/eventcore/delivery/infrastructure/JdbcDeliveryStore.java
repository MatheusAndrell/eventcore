package com.eventcore.delivery.infrastructure;

import com.eventcore.delivery.application.ClaimedDelivery;
import com.eventcore.delivery.application.DeliveryAttempt;
import com.eventcore.delivery.application.DeliveryStore;
import com.eventcore.delivery.domain.DeliveryStatus;
import com.eventcore.platform.identity.ClientId;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcDeliveryStore implements DeliveryStore {

    private final JdbcClient jdbc;

    JdbcDeliveryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean create(UUID deliveryId, UUID eventId, UUID subscriptionId, ClientId clientId, Instant createdAt) {
        return jdbc.sql("""
                        insert into delivery (
                            id, event_id, subscription_id, client_id, status, attempts, created_at, updated_at)
                        values (:id, :eventId, :subscriptionId, :clientId, 'PENDING', 0, :createdAt, :createdAt)
                        on conflict (event_id, subscription_id) do nothing
                        """)
                .param("id", deliveryId)
                .param("eventId", eventId)
                .param("subscriptionId", subscriptionId)
                .param("clientId", clientId.value())
                .param("createdAt", utc(createdAt))
                .update() == 1;
    }

    @Override
    public Optional<ClaimedDelivery> claim(UUID deliveryId, UUID claimToken, Duration lease) {
        return jdbc.sql("""
                        update delivery
                           set claim_token = :claimToken,
                               claimed_until = now() + (:leaseMillis * interval '1 millisecond'),
                               attempts = attempts + 1,
                               updated_at = now()
                         where id = :id and status = 'PENDING' and (claimed_until is null or claimed_until < now())
                        returning id, event_id, subscription_id, claim_token, attempts
                        """)
                .param("claimToken", claimToken)
                .param("leaseMillis", lease.toMillis())
                .param("id", deliveryId)
                .query((row, rowNumber) -> new ClaimedDelivery(
                        row.getObject("id", UUID.class),
                        row.getObject("event_id", UUID.class),
                        row.getObject("subscription_id", UUID.class),
                        row.getObject("claim_token", UUID.class),
                        row.getInt("attempts")))
                .optional();
    }

    @Override
    public boolean cancel(ClaimedDelivery delivery, String reason, Instant now) {
        return finish(delivery, DeliveryStatus.CANCELLED, Optional.of(reason), now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(ClaimedDelivery delivery, DeliveryAttempt attempt, DeliveryStatus status, Instant now) {
        if (!finish(delivery, status, attempt.error(), now)) {
            return false;
        }
        jdbc.sql("""
                        insert into delivery_attempt (
                            id, delivery_id, attempt_number, started_at, duration_ms, succeeded, http_status, error)
                        values (
                            :id, :deliveryId, :attemptNumber, :startedAt, :durationMs, :succeeded, :httpStatus, :error)
                        """)
                .param("id", attempt.id())
                .param("deliveryId", delivery.id())
                .param("attemptNumber", attempt.attemptNumber())
                .param("startedAt", utc(attempt.startedAt()))
                .param("durationMs", attempt.duration().toMillis())
                .param("succeeded", attempt.succeeded())
                .param("httpStatus", attempt.httpStatus().orElse(null))
                .param("error", attempt.error().orElse(null))
                .update();
        return true;
    }

    private boolean finish(ClaimedDelivery delivery, DeliveryStatus status, Optional<String> error, Instant now) {
        return jdbc.sql("""
                        update delivery
                           set status = :status,
                               claim_token = null,
                               claimed_until = null,
                               last_error = :error,
                               updated_at = :now,
                               completed_at = case when :status = 'PENDING' then null else :now end
                         where id = :id and claim_token = :claimToken
                        """)
                .param("status", status.name())
                .param("error", error.orElse(null))
                .param("now", utc(now))
                .param("id", delivery.id())
                .param("claimToken", delivery.claimToken())
                .update() == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
