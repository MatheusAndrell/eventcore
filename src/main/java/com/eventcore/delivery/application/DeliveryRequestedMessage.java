package com.eventcore.delivery.application;

import com.eventcore.outbox.application.OutboxMessage;
import com.eventcore.platform.messaging.MessagingTopology;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class DeliveryRequestedMessage {

    public static final String MESSAGE_TYPE = "delivery.requested";
    public static final int MESSAGE_VERSION = 1;
    public static final String DELIVERY_ID_FIELD = "deliveryId";
    private static final String AGGREGATE_TYPE = "delivery";

    private DeliveryRequestedMessage() {
    }

    static OutboxMessage of(UUID messageId, UUID deliveryId, Instant createdAt) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put(DELIVERY_ID_FIELD, deliveryId.toString());
        return new OutboxMessage(messageId, AGGREGATE_TYPE, deliveryId, MessagingTopology.EVENTS_EXCHANGE,
                MessagingTopology.DELIVERY_REQUESTED_ROUTING_KEY, MESSAGE_TYPE, MESSAGE_VERSION, payload, createdAt);
    }
}
