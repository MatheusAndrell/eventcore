package com.eventcore.ingestion.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record EventPage(List<EventSummary> items, Optional<EventCursor> next) {

    public EventPage {
        items = List.copyOf(items);
        Objects.requireNonNull(next, "next");
    }
}
