package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.Event;
import com.eventcore.ingestion.domain.EventId;
import com.eventcore.platform.identity.ClientId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class EventQueries {

    public static final int MAX_PAGE_SIZE = 100;

    private final EventStore store;

    public EventQueries(EventStore store) {
        this.store = store;
    }

    public Optional<Event> find(ClientId clientId, EventId eventId) {
        return store.findEvent(clientId, eventId);
    }

    public EventPage list(ClientId clientId, EventCriteria criteria, Optional<EventCursor> after, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        List<EventSummary> rows = store.listEvents(clientId, criteria, after, limit + 1);
        if (rows.size() <= limit) {
            return new EventPage(rows, Optional.empty());
        }
        List<EventSummary> page = rows.subList(0, limit);
        EventSummary last = page.getLast();
        return new EventPage(page, Optional.of(new EventCursor(last.receivedAt(), last.id())));
    }
}
