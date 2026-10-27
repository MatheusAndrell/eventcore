package com.eventcore.ingestion.infrastructure;

import com.eventcore.ingestion.application.EventCriteria;
import com.eventcore.ingestion.application.EventCursor;
import com.eventcore.ingestion.application.EventStore;
import com.eventcore.ingestion.application.EventSummary;
import com.eventcore.ingestion.application.IdempotencyKeyLockedException;
import com.eventcore.ingestion.application.IdempotencyRecord;
import com.eventcore.ingestion.domain.DataVersion;
import com.eventcore.ingestion.domain.Event;
import com.eventcore.ingestion.domain.EventData;
import com.eventcore.ingestion.domain.EventId;
import com.eventcore.ingestion.domain.EventSource;
import com.eventcore.ingestion.domain.EventStatus;
import com.eventcore.ingestion.domain.EventSubject;
import com.eventcore.ingestion.domain.EventType;
import com.eventcore.ingestion.domain.IdempotencyKey;
import com.eventcore.ingestion.domain.RequestFingerprint;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.identity.CorrelationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcEventStore implements EventStore {

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;

    JdbcEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IdempotencyRecord> findIdempotencyRecord(ClientId clientId, IdempotencyKey key) {
        return jdbc.sql("""
                        select client_id, idempotency_key, request_fingerprint, event_id, created_at, expires_at
                          from idempotency_record
                         where client_id = :clientId and idempotency_key = :key
                        """)
                .param("clientId", clientId.value())
                .param("key", key.value())
                .query(JdbcEventStore::idempotencyRecord)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean registerIdempotencyKey(IdempotencyRecord record, Duration lockTimeout) {
        jdbc.sql("select set_config('lock_timeout', :timeout, true)")
                .param("timeout", lockTimeout.toMillis() + "ms")
                .query()
                .singleValue();
        try {
            return jdbc.sql("""
                            insert into idempotency_record (
                                client_id, idempotency_key, request_fingerprint, event_id, created_at, expires_at)
                            values (:clientId, :key, :fingerprint, :eventId, :createdAt, :expiresAt)
                            on conflict (client_id, idempotency_key) do nothing
                            """)
                    .param("clientId", record.clientId().value())
                    .param("key", record.key().value())
                    .param("fingerprint", record.fingerprint().bytes())
                    .param("eventId", record.eventId().value())
                    .param("createdAt", utc(record.createdAt()))
                    .param("expiresAt", utc(record.expiresAt()))
                    .update() == 1;
        } catch (DataAccessException exception) {
            if (isLockTimeout(exception)) {
                throw new IdempotencyKeyLockedException(exception);
            }
            throw exception;
        }
    }

    private static boolean isLockTimeout(DataAccessException exception) {
        return exception.getMostSpecificCause() instanceof SQLException sqlException
                && LOCK_NOT_AVAILABLE.equals(sqlException.getSQLState());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(Event event) {
        jdbc.sql("""
                        insert into event (
                            id, client_id, type, data_version, source, subject,
                            occurred_at, received_at, data, status, correlation_id)
                        values (
                            :id, :clientId, :type, :dataVersion, :source, :subject,
                            :occurredAt, :receivedAt, cast(:data as jsonb), :status, :correlationId)
                        """)
                .param("id", event.id().value())
                .param("clientId", event.clientId().value())
                .param("type", event.type().value())
                .param("dataVersion", event.dataVersion().value())
                .param("source", event.source().value())
                .param("subject", event.subject().map(EventSubject::value).orElse(null))
                .param("occurredAt", utc(event.occurredAt()))
                .param("receivedAt", utc(event.receivedAt()))
                .param("data", event.data().json())
                .param("status", event.status().name())
                .param("correlationId", event.correlationId().value())
                .update();
    }

    @Override
    public Optional<Event> findEvent(ClientId clientId, EventId eventId) {
        return jdbc.sql("""
                        select id, client_id, type, data_version, source, subject,
                               occurred_at, received_at, data::text as data, status, correlation_id
                          from event
                         where client_id = :clientId and id = :id
                        """)
                .param("clientId", clientId.value())
                .param("id", eventId.value())
                .query(JdbcEventStore::event)
                .optional();
    }

    @Override
    public Optional<Event> findEvent(EventId eventId) {
        return jdbc.sql("""
                        select id, client_id, type, data_version, source, subject,
                               occurred_at, received_at, data::text as data, status, correlation_id
                          from event
                         where id = :id
                        """)
                .param("id", eventId.value())
                .query(JdbcEventStore::event)
                .optional();
    }

    @Override
    public List<EventSummary> listEvents(
            ClientId clientId, EventCriteria criteria, Optional<EventCursor> after, int limit) {
        return jdbc.sql("""
                        select id, type, data_version, source, subject, occurred_at, received_at, status, correlation_id
                          from event
                         where client_id = :clientId
                           and (cast(:type as text) is null or type = :type)
                           and (cast(:status as text) is null or status = :status)
                           and (cast(:receivedFrom as timestamptz) is null or received_at >= :receivedFrom)
                           and (cast(:receivedBefore as timestamptz) is null or received_at < :receivedBefore)
                           and (cast(:afterReceivedAt as timestamptz) is null
                                or (received_at, id) < (:afterReceivedAt, :afterId))
                         order by received_at desc, id desc
                         limit :limit
                        """)
                .param("clientId", clientId.value())
                .param("type", criteria.type().map(EventType::value).orElse(null), Types.VARCHAR)
                .param("status", criteria.status().map(EventStatus::name).orElse(null), Types.VARCHAR)
                .param("receivedFrom", criteria.receivedFrom().map(JdbcEventStore::utc).orElse(null),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("receivedBefore", criteria.receivedBefore().map(JdbcEventStore::utc).orElse(null),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("afterReceivedAt", after.map(cursor -> utc(cursor.receivedAt())).orElse(null),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("afterId", after.map(cursor -> cursor.id().value()).orElse(null), Types.OTHER)
                .param("limit", limit)
                .query(JdbcEventStore::summary)
                .list();
    }

    private static IdempotencyRecord idempotencyRecord(ResultSet row, int rowNumber) throws SQLException {
        return new IdempotencyRecord(
                new ClientId(row.getObject("client_id", UUID.class)),
                new IdempotencyKey(row.getString("idempotency_key")),
                new RequestFingerprint(row.getBytes("request_fingerprint")),
                new EventId(row.getObject("event_id", UUID.class)),
                instant(row, "created_at"),
                instant(row, "expires_at"));
    }

    private static Event event(ResultSet row, int rowNumber) throws SQLException {
        return new Event(
                new EventId(row.getObject("id", UUID.class)),
                new ClientId(row.getObject("client_id", UUID.class)),
                new EventType(row.getString("type")),
                new DataVersion(row.getInt("data_version")),
                new EventSource(row.getString("source")),
                Optional.ofNullable(row.getString("subject")).map(EventSubject::new),
                instant(row, "occurred_at"),
                instant(row, "received_at"),
                new EventData(row.getString("data")),
                EventStatus.valueOf(row.getString("status")),
                new CorrelationId(row.getString("correlation_id")));
    }

    private static EventSummary summary(ResultSet row, int rowNumber) throws SQLException {
        return new EventSummary(
                new EventId(row.getObject("id", UUID.class)),
                new EventType(row.getString("type")),
                new DataVersion(row.getInt("data_version")),
                new EventSource(row.getString("source")),
                Optional.ofNullable(row.getString("subject")).map(EventSubject::new),
                instant(row, "occurred_at"),
                instant(row, "received_at"),
                EventStatus.valueOf(row.getString("status")),
                new CorrelationId(row.getString("correlation_id")));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
