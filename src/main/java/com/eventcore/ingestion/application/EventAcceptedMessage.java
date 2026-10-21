package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.Event;
import com.eventcore.outbox.application.OutboxMessage;
import java.util.UUID;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

final class EventAcceptedMessage {

    private static final String AGGREGATE_TYPE = "event";
    private static final String EXCHANGE = "eventcore.events";
    private static final String ROUTING_KEY = "event.accepted";
    private static final String MESSAGE_TYPE = "event.accepted";
    private static final int MESSAGE_VERSION = 1;

    private EventAcceptedMessage() {
    }

    static OutboxMessage of(UUID messageId, Event event) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("eventId", event.id().toString());
        return new OutboxMessage(messageId, AGGREGATE_TYPE, event.id().value(), EXCHANGE, ROUTING_KEY,
                MESSAGE_TYPE, MESSAGE_VERSION, payload, event.receivedAt());
    }
}
