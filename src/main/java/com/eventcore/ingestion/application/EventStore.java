package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.Event;
import com.eventcore.ingestion.domain.EventId;
import com.eventcore.ingestion.domain.IdempotencyKey;
import com.eventcore.platform.identity.ClientId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface EventStore {

    Optional<IdempotencyRecord> findIdempotencyRecord(ClientId clientId, IdempotencyKey key);

    boolean registerIdempotencyKey(IdempotencyRecord record, Duration lockTimeout);

    void insert(Event event);

    Optional<Event> findEvent(ClientId clientId, EventId eventId);

    Optional<Event> findEvent(EventId eventId);

    boolean markDispatched(EventId eventId);

    List<Event> claimUndispatched(Duration staleAfter, int limit);

    int deleteExpiredIdempotencyRecords(int limit);

    int deleteDispatchedEventsWithoutDeliveries(Duration age, int limit);

    List<EventSummary> listEvents(ClientId clientId, EventCriteria criteria, Optional<EventCursor> after, int limit);
}
