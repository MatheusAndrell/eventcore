package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.DataVersion;
import com.eventcore.ingestion.domain.Event;
import com.eventcore.ingestion.domain.EventId;
import com.eventcore.ingestion.domain.EventStatus;
import com.eventcore.ingestion.domain.EventType;
import java.time.Instant;

public record EventReceipt(EventId id, EventType type, DataVersion dataVersion, EventStatus status, Instant receivedAt) {

    static EventReceipt of(Event event) {
        return new EventReceipt(event.id(), event.type(), event.dataVersion(), EventStatus.RECEIVED, event.receivedAt());
    }
}
