package com.eventcore.outbox.infrastructure;

import com.eventcore.outbox.application.MessagePublisher;
import com.eventcore.outbox.application.PendingMessage;
import com.eventcore.outbox.application.PublishOutcome;
import com.eventcore.platform.messaging.MessageHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
class RabbitMessagePublisher implements MessagePublisher {

    private final RabbitTemplate template;

    RabbitMessagePublisher(RabbitTemplate template) {
        this.template = template;
    }

    @Override
    public Map<UUID, PublishOutcome> publish(List<PendingMessage> messages, Duration confirmTimeout) {
        long deadline = System.nanoTime() + confirmTimeout.toNanos();
        Map<UUID, CorrelationData> awaiting = new LinkedHashMap<>();
        Map<UUID, PublishOutcome> outcomes = new HashMap<>();
        for (PendingMessage message : messages) {
            CorrelationData correlation = new CorrelationData(message.id().toString());
            try {
                template.send(message.exchange(), message.routingKey(), toAmqpMessage(message), correlation);
                awaiting.put(message.id(), correlation);
            } catch (AmqpException exception) {
                outcomes.put(message.id(), new PublishOutcome.Failed(describe(exception)));
            }
        }
        awaiting.forEach((id, correlation) -> outcomes.put(id, await(correlation, deadline)));
        return outcomes;
    }

    private static PublishOutcome await(CorrelationData correlation, long deadline) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!confirm.ack()) {
                return new PublishOutcome.Nacked(String.valueOf(confirm.reason()));
            }
            ReturnedMessage returned = correlation.getReturned();
            if (returned != null) {
                return new PublishOutcome.Returned(returned.getReplyCode(), returned.getReplyText());
            }
            return new PublishOutcome.Confirmed();
        } catch (TimeoutException exception) {
            return new PublishOutcome.TimedOut();
        } catch (ExecutionException exception) {
            return new PublishOutcome.Failed(describe(exception.getCause()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new PublishOutcome.TimedOut();
        }
    }

    private static Message toAmqpMessage(PendingMessage message) {
        return MessageBuilder.withBody(message.payload().getBytes(StandardCharsets.UTF_8))
                .setMessageId(message.id().toString())
                .setType(message.messageType())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setTimestamp(Date.from(message.createdAt()))
                .setHeader(MessageHeaders.MESSAGE_VERSION, message.messageVersion())
                .setHeader(MessageHeaders.AGGREGATE_ID, message.aggregateId().toString())
                .build();
    }

    private static String describe(Throwable exception) {
        return exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }
}
