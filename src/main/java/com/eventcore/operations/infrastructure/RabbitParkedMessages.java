package com.eventcore.operations.infrastructure;

import com.eventcore.operations.application.BrokerUnavailableException;
import com.eventcore.operations.application.ParkedMessages;
import com.eventcore.operations.application.ReplayResult;
import com.eventcore.platform.messaging.MessagingTopology;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.stereotype.Component;

@Component
class RabbitParkedMessages implements ParkedMessages {

    private static final Logger log = LoggerFactory.getLogger(RabbitParkedMessages.class);
    private static final String QUEUE = MessagingTopology.PARKING_QUEUE;
    private static final String ORIGIN_QUEUE_HEADER = "x-first-death-queue";
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    private static final String CONNECTION_NAME = "eventcore-parking-operations";

    private final CachingConnectionFactory connectionFactory;
    private final AmqpAdmin amqpAdmin;

    RabbitParkedMessages(CachingConnectionFactory connectionFactory, AmqpAdmin amqpAdmin) {
        this.connectionFactory = connectionFactory;
        this.amqpAdmin = amqpAdmin;
    }

    @Override
    public long count() {
        try {
            QueueInformation queue = amqpAdmin.getQueueInfo(QUEUE);
            return queue == null ? 0 : queue.getMessageCount();
        } catch (AmqpException exception) {
            throw new BrokerUnavailableException("could not read the parking queue", exception);
        }
    }

    @Override
    public ReplayResult replay(int limit) {
        try (Connection connection = connect(); Channel channel = connection.createChannel()) {
            channel.confirmSelect();
            AtomicBoolean returned = new AtomicBoolean();
            channel.addReturnListener(message -> returned.set(true));
            int replayed = 0;
            Optional<String> stoppedBecause = Optional.empty();
            while (replayed < limit && stoppedBecause.isEmpty()) {
                GetResponse message = channel.basicGet(QUEUE, false);
                if (message == null) {
                    break;
                }
                stoppedBecause = republish(channel, message, returned);
                if (stoppedBecause.isEmpty()) {
                    replayed++;
                }
            }
            long remaining = channel.messageCount(QUEUE);
            log.warn("replayed {} parked messages; {} remain{}", replayed, remaining,
                    stoppedBecause.map(reason -> "; stopped because " + reason).orElse(""));
            return new ReplayResult(replayed, remaining, stoppedBecause);
        } catch (IOException | TimeoutException exception) {
            throw new BrokerUnavailableException("could not replay the parking queue", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("interrupted while replaying the parking queue", exception);
        }
    }

    private static Optional<String> republish(Channel channel, GetResponse message, AtomicBoolean returned)
            throws IOException, InterruptedException, TimeoutException {
        long tag = message.getEnvelope().getDeliveryTag();
        Optional<String> origin = originQueue(message.getProps());
        if (origin.isEmpty()) {
            channel.basicNack(tag, false, true);
            return Optional.of("message " + message.getProps().getMessageId() + " has no dead-letter origin");
        }
        returned.set(false);
        channel.basicPublish("", origin.get(), true, withoutDeathHeaders(message.getProps()), message.getBody());
        channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT.toMillis());
        if (returned.get()) {
            channel.basicNack(tag, false, true);
            return Optional.of("queue '" + origin.get() + "' does not exist");
        }
        channel.basicAck(tag, false);
        return Optional.empty();
    }

    private static Optional<String> originQueue(AMQP.BasicProperties properties) {
        Map<String, Object> headers = properties.getHeaders();
        return Optional.ofNullable(headers == null ? null : headers.get(ORIGIN_QUEUE_HEADER)).map(Object::toString);
    }

    private static AMQP.BasicProperties withoutDeathHeaders(AMQP.BasicProperties properties) {
        Map<String, Object> headers = new HashMap<>(properties.getHeaders());
        headers.keySet().removeIf(name -> name.equals("x-death") || name.startsWith("x-first-death-")
                || name.startsWith("x-last-death-"));
        return properties.builder().headers(headers).build();
    }

    private Connection connect() throws IOException, TimeoutException {
        return connectionFactory.getRabbitConnectionFactory().newConnection(CONNECTION_NAME);
    }
}
