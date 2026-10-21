package com.eventcore.ingestion.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record EventSubmission(
        EventType type,
        DataVersion dataVersion,
        EventSource source,
        Optional<EventSubject> subject,
        Instant occurredAt,
        EventData data) {

    public EventSubmission {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dataVersion, "dataVersion");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(data, "data");
    }
}
