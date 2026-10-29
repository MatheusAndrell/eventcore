package com.eventcore.outbox.infrastructure;

import com.eventcore.outbox.application.OutboxStore;
import com.eventcore.outbox.application.PendingMessage;
import com.eventcore.platform.json.StrictJsonReader;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOutboxStore implements OutboxStore {

    private final JdbcClient jdbc;
    private final StrictJsonReader jsonReader;

    JdbcOutboxStore(JdbcClient jdbc, StrictJsonReader jsonReader) {
        this.jdbc = jdbc;
        this.jsonReader = jsonReader;
    }

    @Override
    public List<PendingMessage> claim(UUID claimToken, Duration lease, int batchSize) {
        return jdbc.sql("""
                        update outbox_message o
                           set claim_token = :claimToken,
                               claimed_until = now() + (:leaseMillis * interval '1 millisecond')
                         where o.id in (
                               select id from outbox_message
                                where status = 'PENDING'
                                  and available_at <= now()
                                  and (claimed_until is null or claimed_until < now())
                                order by available_at, id
                                limit :batchSize
                                  for update skip locked)
                        returning o.id, o.exchange, o.routing_key, o.message_type, o.message_version,
                                  o.aggregate_id, o.payload::text as payload, o.created_at, o.publish_attempts,
                                  o.trace_context::text as trace_context
                        """)
                .param("claimToken", claimToken)
                .param("leaseMillis", lease.toMillis())
                .param("batchSize", batchSize)
                .query(this::pendingMessage)
                .list();
    }

    @Override
    public boolean markPublished(UUID messageId, UUID claimToken) {
        return jdbc.sql("""
                        update outbox_message
                           set status = 'PUBLISHED', published_at = now(), claim_token = null, claimed_until = null,
                               last_error = null
                         where id = :id and claim_token = :claimToken and status = 'PENDING'
                        """)
                .param("id", messageId)
                .param("claimToken", claimToken)
                .update() == 1;
    }

    @Override
    public boolean reschedule(UUID messageId, UUID claimToken, Duration delay, String error) {
        return jdbc.sql("""
                        update outbox_message
                           set publish_attempts = publish_attempts + 1,
                               available_at = now() + (:delayMillis * interval '1 millisecond'),
                               last_error = :error, claim_token = null, claimed_until = null
                         where id = :id and claim_token = :claimToken and status = 'PENDING'
                        """)
                .param("delayMillis", delay.toMillis())
                .param("error", error)
                .param("id", messageId)
                .param("claimToken", claimToken)
                .update() == 1;
    }

    @Override
    public long countPending() {
        return jdbc.sql("select count(*) from outbox_message where status = 'PENDING'").query(Long.class).single();
    }

    @Override
    public Optional<Duration> oldestPendingAge() {
        return jdbc.sql("""
                        select extract(epoch from now() - min(created_at)) * 1000
                          from outbox_message where status = 'PENDING'
                        having count(*) > 0
                        """)
                .query(Double.class)
                .optional()
                .map(millis -> Duration.ofMillis(Math.max(0, Math.round(millis))));
    }

    @Override
    public int deletePublishedBefore(Duration age, int limit) {
        return jdbc.sql("""
                        delete from outbox_message where id in (
                            select id from outbox_message
                             where status = 'PUBLISHED'
                               and published_at < now() - (:ageMillis * interval '1 millisecond')
                             limit :limit
                               for update skip locked)
                        """)
                .param("ageMillis", age.toMillis())
                .param("limit", limit)
                .update();
    }

    private Map<String, String> traceContext(String json) {
        if (json == null) {
            return Map.of();
        }
        Map<String, String> context = new LinkedHashMap<>();
        jsonReader.read(json.getBytes(StandardCharsets.UTF_8)).properties()
                .forEach(entry -> context.put(entry.getKey(), entry.getValue().asString()));
        return context;
    }

    private PendingMessage pendingMessage(ResultSet row, int rowNumber) throws SQLException {
        return new PendingMessage(
                row.getObject("id", UUID.class),
                row.getString("exchange"),
                row.getString("routing_key"),
                row.getString("message_type"),
                row.getInt("message_version"),
                row.getObject("aggregate_id", UUID.class),
                row.getString("payload"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getInt("publish_attempts"),
                traceContext(row.getString("trace_context")));
    }
}
