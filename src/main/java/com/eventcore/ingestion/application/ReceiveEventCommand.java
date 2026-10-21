package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.IdempotencyKey;
import com.eventcore.platform.identity.ClientId;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

public record ReceiveEventCommand(ClientId clientId, IdempotencyKey idempotencyKey, JsonNode body) {

    public ReceiveEventCommand {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(body, "body");
    }
}
