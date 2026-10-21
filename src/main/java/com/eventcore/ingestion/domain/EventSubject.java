package com.eventcore.ingestion.domain;

public record EventSubject(String value) {

    public static final int MAX_LENGTH = 256;

    public EventSubject {
        if (value == null || value.isBlank()) {
            throw new InvalidEventAttributeException("subject", "must not be blank when present");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidEventAttributeException("subject", "must have at most " + MAX_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
