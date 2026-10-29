package com.eventcore.outbox.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record PendingMessage(
        UUID id,
        String exchange,
        String routingKey,
        String messageType,
        int messageVersion,
        UUID aggregateId,
        String payload,
        Instant createdAt,
        int publishAttempts,
        Map<String, String> traceContext) {

    public PendingMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        traceContext = Map.copyOf(traceContext);
    }
}
