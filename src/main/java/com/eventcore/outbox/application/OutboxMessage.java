package com.eventcore.outbox.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record OutboxMessage(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String exchange,
        String routingKey,
        String messageType,
        int messageVersion,
        JsonNode payload,
        Instant createdAt) {

    public OutboxMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        if (messageVersion < 1) {
            throw new IllegalArgumentException("messageVersion must be positive: " + messageVersion);
        }
        if (!payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
    }
}
