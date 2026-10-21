package com.eventcore.ingestion.domain;

import com.eventcore.platform.identity.ClientId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record Event(
        EventId id,
        ClientId clientId,
        EventType type,
        DataVersion dataVersion,
        EventSource source,
        Optional<EventSubject> subject,
        Instant occurredAt,
        Instant receivedAt,
        EventData data,
        EventStatus status) {

    public Event {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dataVersion, "dataVersion");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(status, "status");
    }

    public static Event receive(
            EventId id, ClientId clientId, EventSubmission submission, Instant receivedAt, Duration clockSkewTolerance) {
        if (submission.occurredAt().isAfter(receivedAt.plus(clockSkewTolerance))) {
            throw new EventOccursInFutureException(submission.occurredAt(), receivedAt, clockSkewTolerance);
        }
        return new Event(id, clientId, submission.type(), submission.dataVersion(), submission.source(),
                submission.subject(), submission.occurredAt(), receivedAt, submission.data(), EventStatus.RECEIVED);
    }
}
