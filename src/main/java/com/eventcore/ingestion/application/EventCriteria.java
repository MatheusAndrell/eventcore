package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.EventStatus;
import com.eventcore.ingestion.domain.EventType;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record EventCriteria(
        Optional<EventType> type,
        Optional<EventStatus> status,
        Optional<Instant> receivedFrom,
        Optional<Instant> receivedBefore) {

    public EventCriteria {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(receivedFrom, "receivedFrom");
        Objects.requireNonNull(receivedBefore, "receivedBefore");
        if (receivedFrom.isPresent() && receivedBefore.isPresent()
                && !receivedFrom.get().isBefore(receivedBefore.get())) {
            throw new IllegalArgumentException("receivedFrom must be before receivedBefore");
        }
    }
}
