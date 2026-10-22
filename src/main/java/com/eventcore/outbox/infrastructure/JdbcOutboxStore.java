package com.eventcore.outbox.infrastructure;

import com.eventcore.outbox.application.OutboxStore;
import com.eventcore.outbox.application.PendingMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOutboxStore implements OutboxStore {

    private final JdbcClient jdbc;

    JdbcOutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
                                  o.aggregate_id, o.payload::text as payload, o.created_at, o.publish_attempts
                        """)
                .param("claimToken", claimToken)
                .param("leaseMillis", lease.toMillis())
                .param("batchSize", batchSize)
                .query(JdbcOutboxStore::pendingMessage)
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

    private static PendingMessage pendingMessage(ResultSet row, int rowNumber) throws SQLException {
        return new PendingMessage(
                row.getObject("id", UUID.class),
                row.getString("exchange"),
                row.getString("routing_key"),
                row.getString("message_type"),
                row.getInt("message_version"),
                row.getObject("aggregate_id", UUID.class),
                row.getString("payload"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getInt("publish_attempts"));
    }
}
