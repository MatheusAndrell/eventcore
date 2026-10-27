package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.Event;
import com.eventcore.outbox.application.OutboxMessage;
import com.eventcore.platform.messaging.MessagingTopology;
import java.util.UUID;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class EventAcceptedMessage {

    public static final String MESSAGE_TYPE = "event.accepted";
    public static final int MESSAGE_VERSION = 1;
    public static final String EVENT_ID_FIELD = "eventId";
    private static final String AGGREGATE_TYPE = "event";

    private EventAcceptedMessage() {
    }

    static OutboxMessage of(UUID messageId, Event event) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put(EVENT_ID_FIELD, event.id().toString());
        return new OutboxMessage(messageId, AGGREGATE_TYPE, event.id().value(), MessagingTopology.EVENTS_EXCHANGE,
                MessagingTopology.EVENT_ACCEPTED_ROUTING_KEY, MESSAGE_TYPE, MESSAGE_VERSION, payload,
                event.receivedAt());
    }
}
