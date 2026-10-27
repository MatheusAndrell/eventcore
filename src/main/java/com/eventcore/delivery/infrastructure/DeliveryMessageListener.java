package com.eventcore.delivery.infrastructure;

import com.eventcore.delivery.application.DeliverWebhook;
import com.eventcore.delivery.application.DeliveryRequestedMessage;
import com.eventcore.delivery.application.DispatchEvent;
import com.eventcore.delivery.application.UnknownEventException;
import com.eventcore.ingestion.application.EventAcceptedMessage;
import com.eventcore.platform.json.InvalidJsonException;
import com.eventcore.platform.json.StrictJsonReader;
import com.eventcore.platform.messaging.MessageHeaders;
import com.eventcore.platform.messaging.MessagingTopology;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import java.util.Objects;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnRole(Role.WORKER)
class DeliveryMessageListener {

    private final DispatchEvent dispatchEvent;
    private final DeliverWebhook deliverWebhook;
    private final StrictJsonReader jsonReader;

    DeliveryMessageListener(DispatchEvent dispatchEvent, DeliverWebhook deliverWebhook, StrictJsonReader jsonReader) {
        this.dispatchEvent = dispatchEvent;
        this.deliverWebhook = deliverWebhook;
        this.jsonReader = jsonReader;
    }

    @RabbitListener(queues = MessagingTopology.EVENT_ACCEPTED_QUEUE)
    void onEventAccepted(Message message) {
        UUID eventId = identifier(message, EventAcceptedMessage.MESSAGE_TYPE, EventAcceptedMessage.MESSAGE_VERSION,
                EventAcceptedMessage.EVENT_ID_FIELD);
        try {
            dispatchEvent.dispatch(eventId);
        } catch (UnknownEventException exception) {
            throw new AmqpRejectAndDontRequeueException(exception.getMessage(), exception);
        }
    }

    @RabbitListener(queues = MessagingTopology.DELIVERY_REQUESTED_QUEUE)
    void onDeliveryRequested(Message message) {
        deliverWebhook.deliver(identifier(message, DeliveryRequestedMessage.MESSAGE_TYPE,
                DeliveryRequestedMessage.MESSAGE_VERSION, DeliveryRequestedMessage.DELIVERY_ID_FIELD));
    }

    private UUID identifier(Message message, String expectedType, int expectedVersion, String field) {
        MessageProperties properties = message.getMessageProperties();
        if (!expectedType.equals(properties.getType())) {
            throw unprocessable(message, "unexpected message type '" + properties.getType() + "'", null);
        }
        Object version = properties.getHeader(MessageHeaders.MESSAGE_VERSION);
        if (!Objects.equals(version, expectedVersion)) {
            throw unprocessable(message, "unsupported message version '" + version + "'", null);
        }
        JsonNode value;
        try {
            value = jsonReader.read(message.getBody()).get(field);
        } catch (InvalidJsonException exception) {
            throw unprocessable(message, "body is not valid JSON", exception);
        }
        if (value == null || !value.isString()) {
            throw unprocessable(message, "missing '" + field + "'", null);
        }
        try {
            return UUID.fromString(value.stringValue());
        } catch (IllegalArgumentException exception) {
            throw unprocessable(message, "'" + field + "' is not a UUID", exception);
        }
    }

    private static AmqpRejectAndDontRequeueException unprocessable(Message message, String reason, Throwable cause) {
        return new AmqpRejectAndDontRequeueException(
                "message " + message.getMessageProperties().getMessageId() + " is not processable: " + reason, cause);
    }
}
