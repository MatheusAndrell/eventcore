package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.IdempotencyKey;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.identity.CorrelationId;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

public record ReceiveEventCommand(
        ClientId clientId, IdempotencyKey idempotencyKey, CorrelationId correlationId, JsonNode body) {

    public ReceiveEventCommand {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(body, "body");
    }
}
