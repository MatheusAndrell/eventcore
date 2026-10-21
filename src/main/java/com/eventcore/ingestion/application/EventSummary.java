package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.DataVersion;
import com.eventcore.ingestion.domain.EventId;
import com.eventcore.ingestion.domain.EventSource;
import com.eventcore.ingestion.domain.EventStatus;
import com.eventcore.ingestion.domain.EventSubject;
import com.eventcore.ingestion.domain.EventType;
import com.eventcore.platform.identity.CorrelationId;
import java.time.Instant;
import java.util.Optional;

public record EventSummary(
        EventId id,
        EventType type,
        DataVersion dataVersion,
        EventSource source,
        Optional<EventSubject> subject,
        Instant occurredAt,
        Instant receivedAt,
        EventStatus status,
        CorrelationId correlationId) {
}
