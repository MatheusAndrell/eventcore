package com.eventcore.outbox.infrastructure;

import com.eventcore.outbox.application.Outbox;
import com.eventcore.outbox.application.OutboxMessage;
import com.eventcore.platform.json.JsonCanonicalizer;
import com.eventcore.platform.tracing.TraceContextPropagation;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Repository
class JdbcOutbox implements Outbox {

    private final JdbcClient jdbc;
    private final JsonCanonicalizer canonicalizer;
    private final TraceContextPropagation tracing;

    JdbcOutbox(JdbcClient jdbc, JsonCanonicalizer canonicalizer, TraceContextPropagation tracing) {
        this.jdbc = jdbc;
        this.canonicalizer = canonicalizer;
        this.tracing = tracing;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(OutboxMessage message) {
        insert(message, Duration.ZERO);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueAfter(OutboxMessage message, Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative: " + delay);
        }
        insert(message, delay);
    }

    private void insert(OutboxMessage message, Duration delay) {
        Map<String, String> traceContext = tracing.current();
        ObjectNode traceNode = JsonNodeFactory.instance.objectNode();
        traceContext.forEach(traceNode::put);
        jdbc.sql("""
                        insert into outbox_message (
                            id, aggregate_type, aggregate_id, exchange, routing_key,
                            message_type, message_version, payload, status, available_at, created_at, trace_context)
                        values (
                            :id, :aggregateType, :aggregateId, :exchange, :routingKey,
                            :messageType, :messageVersion, cast(:payload as jsonb), 'PENDING',
                            now() + (:delayMillis * interval '1 millisecond'), :createdAt,
                            cast(:traceContext as jsonb))
                        """)
                .param("traceContext", traceContext.isEmpty() ? null : canonicalizer.canonicalText(traceNode))
                .param("id", message.id())
                .param("aggregateType", message.aggregateType())
                .param("aggregateId", message.aggregateId())
                .param("exchange", message.exchange())
                .param("routingKey", message.routingKey())
                .param("messageType", message.messageType())
                .param("messageVersion", message.messageVersion())
                .param("payload", canonicalizer.canonicalText(message.payload()))
                .param("delayMillis", delay.toMillis())
                .param("createdAt", message.createdAt().atOffset(ZoneOffset.UTC))
                .update();
    }
}
