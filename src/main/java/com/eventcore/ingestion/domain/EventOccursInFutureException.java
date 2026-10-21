package com.eventcore.ingestion.domain;

import java.time.Duration;
import java.time.Instant;

public class EventOccursInFutureException extends RuntimeException {

    public EventOccursInFutureException(Instant occurredAt, Instant receivedAt, Duration tolerance) {
        super("time " + occurredAt + " is more than " + tolerance + " after reception at " + receivedAt);
    }
}
