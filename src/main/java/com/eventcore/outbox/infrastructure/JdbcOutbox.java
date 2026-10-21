package com.eventcore.outbox.infrastructure;

import com.eventcore.outbox.application.Outbox;
import com.eventcore.outbox.application.OutboxMessage;
import com.eventcore.platform.json.JsonCanonicalizer;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcOutbox implements Outbox {

    private final JdbcClient jdbc;
    private final JsonCanonicalizer canonicalizer;

    JdbcOutbox(JdbcClient jdbc, JsonCanonicalizer canonicalizer) {
        this.jdbc = jdbc;
        this.canonicalizer = canonicalizer;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(OutboxMessage message) {
        jdbc.sql("""
                        insert into outbox_message (
                            id, aggregate_type, aggregate_id, exchange, routing_key,
                            message_type, message_version, payload, status, available_at, created_at)
                        values (
                            :id, :aggregateType, :aggregateId, :exchange, :routingKey,
                            :messageType, :messageVersion, cast(:payload as jsonb), 'PENDING', :createdAt, :createdAt)
                        """)
                .param("id", message.id())
                .param("aggregateType", message.aggregateType())
                .param("aggregateId", message.aggregateId())
                .param("exchange", message.exchange())
                .param("routingKey", message.routingKey())
                .param("messageType", message.messageType())
                .param("messageVersion", message.messageVersion())
                .param("payload", canonicalizer.canonicalText(message.payload()))
                .param("createdAt", message.createdAt().atOffset(ZoneOffset.UTC))
                .update();
    }
}
