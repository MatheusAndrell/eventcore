package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.EventId;
import java.time.Instant;
import java.util.Objects;

public record EventCursor(Instant receivedAt, EventId id) {

    public EventCursor {
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(id, "id");
    }
}
