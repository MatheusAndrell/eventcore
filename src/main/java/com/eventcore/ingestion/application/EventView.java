package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.Event;
import com.eventcore.ingestion.domain.EventSubject;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.identity.CorrelationId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record EventView(
        UUID id,
        ClientId clientId,
        String type,
        int dataVersion,
        String source,
        Optional<String> subject,
        Instant occurredAt,
        Instant receivedAt,
        String data,
        CorrelationId correlationId) {

    public EventView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    static EventView of(Event event) {
        return new EventView(event.id().value(), event.clientId(), event.type().value(), event.dataVersion().value(),
                event.source().value(), event.subject().map(EventSubject::value), event.occurredAt(),
                event.receivedAt(), event.data().json(), event.correlationId());
    }
}
