package com.eventcore.delivery.infrastructure;

import com.eventcore.delivery.application.ClaimedDelivery;
import com.eventcore.delivery.application.DeliveryAttempt;
import com.eventcore.delivery.application.DeliveryStore;
import com.eventcore.delivery.application.DeliverySummary;
import com.eventcore.delivery.domain.DeliveryStatus;
import com.eventcore.platform.identity.ClientId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcDeliveryStore implements DeliveryStore {

    private static final String EXHAUSTED_WITHOUT_RESULT = "attempts exhausted without a recorded result";
    private static final String SUMMARY_COLUMNS = """
            id, event_id, subscription_id, status, attempts, max_attempts, next_attempt_at, last_error,
            created_at, updated_at, completed_at
            """;

    private final JdbcClient jdbc;

    JdbcDeliveryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean create(
            UUID deliveryId, UUID eventId, UUID subscriptionId, ClientId clientId, int maxAttempts, Instant createdAt) {
        return jdbc.sql("""
                        insert into delivery (
                            id, event_id, subscription_id, client_id, status, attempts, max_attempts, next_attempt_at,
                            created_at, updated_at)
                        values (:id, :eventId, :subscriptionId, :clientId, 'PENDING', 0, :maxAttempts, now(),
                                :createdAt, :createdAt)
                        on conflict (event_id, subscription_id) do nothing
                        """)
                .param("id", deliveryId)
                .param("eventId", eventId)
                .param("subscriptionId", subscriptionId)
                .param("clientId", clientId.value())
                .param("maxAttempts", maxAttempts)
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
                         where id = :id
                           and status = 'PENDING'
                           and next_attempt_at <= now()
                           and attempts < max_attempts
                           and (claimed_until is null or claimed_until < now())
                        returning id, event_id, subscription_id, claim_token, attempts, max_attempts
                        """)
                .param("claimToken", claimToken)
                .param("leaseMillis", lease.toMillis())
                .param("id", deliveryId)
                .query((row, rowNumber) -> new ClaimedDelivery(
                        row.getObject("id", UUID.class),
                        row.getObject("event_id", UUID.class),
                        row.getObject("subscription_id", UUID.class),
                        row.getObject("claim_token", UUID.class),
                        row.getInt("attempts"),
                        row.getInt("max_attempts")))
                .optional();
    }

    @Override
    public boolean cancel(ClaimedDelivery delivery, String reason, Instant now) {
        return finish(delivery, DeliveryStatus.CANCELLED, Optional.of(reason), now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(ClaimedDelivery delivery, DeliveryAttempt attempt, DeliveryStatus status, Instant now) {
        if (status == DeliveryStatus.PENDING) {
            throw new IllegalArgumentException("completed deliveries must have a terminal status");
        }
        if (!finish(delivery, status, attempt.error(), now)) {
            return false;
        }
        insertAttempt(delivery, attempt);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean scheduleRetry(ClaimedDelivery delivery, DeliveryAttempt attempt, Duration delay, Instant now) {
        boolean scheduled = jdbc.sql("""
                        update delivery
                           set claim_token = null,
                               claimed_until = null,
                               last_error = :error,
                               next_attempt_at = now() + (:delayMillis * interval '1 millisecond'),
                               updated_at = :now
                         where id = :id and claim_token = :claimToken and status = 'PENDING'
                        """)
                .param("error", attempt.error().orElse(null))
                .param("delayMillis", delay.toMillis())
                .param("now", utc(now))
                .param("id", delivery.id())
                .param("claimToken", delivery.claimToken())
                .update() == 1;
        if (scheduled) {
            insertAttempt(delivery, attempt);
        }
        return scheduled;
    }

    @Override
    public int failExhausted() {
        return jdbc.sql("""
                        update delivery
                           set status = 'FAILED', claim_token = null, claimed_until = null, next_attempt_at = null,
                               last_error = :error, updated_at = now(), completed_at = now()
                         where status = 'PENDING'
                           and attempts >= max_attempts
                           and (claimed_until is null or claimed_until < now())
                        """)
                .param("error", EXHAUSTED_WITHOUT_RESULT)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> claimStale(Duration staleAfter, int limit) {
        return jdbc.sql("""
                        update delivery d set next_attempt_at = now(), updated_at = now()
                         where d.id in (
                               select id from delivery
                                where status = 'PENDING'
                                  and attempts < max_attempts
                                  and next_attempt_at < now() - (:staleMillis * interval '1 millisecond')
                                  and (claimed_until is null or claimed_until < now())
                                order by next_attempt_at, id
                                limit :limit
                                  for update skip locked)
                        returning d.id
                        """)
                .param("staleMillis", staleAfter.toMillis())
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reopen(ClientId clientId, UUID deliveryId, int additionalAttempts) {
        return jdbc.sql("""
                        update delivery
                           set status = 'PENDING', next_attempt_at = now(), completed_at = null,
                               max_attempts = attempts + :additionalAttempts, updated_at = now()
                         where id = :id and client_id = :clientId and status in ('FAILED', 'CANCELLED')
                        """)
                .param("additionalAttempts", additionalAttempts)
                .param("id", deliveryId)
                .param("clientId", clientId.value())
                .update() == 1;
    }

    @Override
    public List<DeliverySummary> listForEvent(ClientId clientId, UUID eventId) {
        return jdbc.sql("select " + SUMMARY_COLUMNS + """
                         from delivery
                        where client_id = :clientId and event_id = :eventId
                        order by created_at, id
                        """)
                .param("clientId", clientId.value())
                .param("eventId", eventId)
                .query(JdbcDeliveryStore::summary)
                .list();
    }

    @Override
    public Optional<DeliverySummary> find(ClientId clientId, UUID deliveryId) {
        return jdbc.sql("select " + SUMMARY_COLUMNS + " from delivery where client_id = :clientId and id = :id")
                .param("clientId", clientId.value())
                .param("id", deliveryId)
                .query(JdbcDeliveryStore::summary)
                .optional();
    }

    @Override
    public List<DeliveryAttempt> attempts(UUID deliveryId) {
        return jdbc.sql("""
                        select id, attempt_number, started_at, duration_ms, http_status, error, succeeded
                          from delivery_attempt
                         where delivery_id = :deliveryId
                         order by attempt_number
                        """)
                .param("deliveryId", deliveryId)
                .query((row, rowNumber) -> new DeliveryAttempt(
                        row.getObject("id", UUID.class),
                        row.getInt("attempt_number"),
                        instant(row, "started_at"),
                        Duration.ofMillis(row.getLong("duration_ms")),
                        Optional.ofNullable(row.getObject("http_status", Integer.class)),
                        Optional.ofNullable(row.getString("error")),
                        row.getBoolean("succeeded")))
                .list();
    }

    @Override
    public long countPending() {
        return jdbc.sql("select count(*) from delivery where status = 'PENDING'").query(Long.class).single();
    }

    @Override
    public long countDue() {
        return jdbc.sql("select count(*) from delivery where status = 'PENDING' and next_attempt_at <= now()")
                .query(Long.class)
                .single();
    }

    @Override
    public int deleteCompletedBefore(Duration age, int limit) {
        return jdbc.sql("""
                        with expired as (
                            select id from delivery
                             where status <> 'PENDING'
                               and completed_at < now() - (:ageMillis * interval '1 millisecond')
                             limit :limit
                               for update skip locked),
                        attempts as (
                            delete from delivery_attempt where delivery_id in (select id from expired))
                        delete from delivery where id in (select id from expired)
                        """)
                .param("ageMillis", age.toMillis())
                .param("limit", limit)
                .update();
    }

    private void insertAttempt(ClaimedDelivery delivery, DeliveryAttempt attempt) {
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
    }

    private boolean finish(ClaimedDelivery delivery, DeliveryStatus status, Optional<String> error, Instant now) {
        return jdbc.sql("""
                        update delivery
                           set status = :status,
                               claim_token = null,
                               claimed_until = null,
                               next_attempt_at = null,
                               last_error = :error,
                               updated_at = :now,
                               completed_at = :now
                         where id = :id and claim_token = :claimToken
                        """)
                .param("status", status.name())
                .param("error", error.orElse(null))
                .param("now", utc(now))
                .param("id", delivery.id())
                .param("claimToken", delivery.claimToken())
                .update() == 1;
    }

    private static DeliverySummary summary(ResultSet row, int rowNumber) throws SQLException {
        return new DeliverySummary(
                row.getObject("id", UUID.class),
                row.getObject("event_id", UUID.class),
                row.getObject("subscription_id", UUID.class),
                DeliveryStatus.valueOf(row.getString("status")),
                row.getInt("attempts"),
                row.getInt("max_attempts"),
                optionalInstant(row, "next_attempt_at"),
                Optional.ofNullable(row.getString("last_error")),
                instant(row, "created_at"),
                instant(row, "updated_at"),
                optionalInstant(row, "completed_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Optional<Instant> optionalInstant(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, OffsetDateTime.class)).map(OffsetDateTime::toInstant);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
