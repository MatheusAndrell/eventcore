package com.eventcore.support;

import com.eventcore.platform.messaging.MessagingTopology;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class OutboxTestSupport {

    private static final Duration RECEIVE_TIMEOUT = Duration.ofMillis(500);

    private OutboxTestSupport() {
    }

    public static UUID insertPending(JdbcClient jdbc, String exchange, String routingKey) {
        UUID id = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        jdbc.sql("""
                        insert into outbox_message (
                            id, aggregate_type, aggregate_id, exchange, routing_key, message_type, message_version,
                            payload, status, available_at, created_at)
                        values (:id, 'event', :aggregateId, :exchange, :routingKey, 'event.accepted', 1,
                                cast(:payload as jsonb), 'PENDING', now(), now())
                        """)
                .param("id", id)
                .param("aggregateId", aggregateId)
                .param("exchange", exchange)
                .param("routingKey", routingKey)
                .param("payload", "{\"eventId\":\"" + aggregateId + "\"}")
                .update();
        return id;
    }

    public static UUID insertPendingEventAccepted(JdbcClient jdbc) {
        return insertPending(jdbc, MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.EVENT_ACCEPTED_ROUTING_KEY);
    }

    public static List<Message> drain(RabbitTemplate rabbit, String queue) {
        List<Message> messages = new ArrayList<>();
        Message message;
        while ((message = rabbit.receive(queue, RECEIVE_TIMEOUT.toMillis())) != null) {
            messages.add(message);
        }
        return messages;
    }

    public static String status(JdbcClient jdbc, UUID id) {
        return jdbc.sql("select status from outbox_message where id = :id").param("id", id).query(String.class)
                .single();
    }
}
