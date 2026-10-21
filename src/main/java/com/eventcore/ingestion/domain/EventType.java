package com.eventcore.ingestion.domain;

public record EventType(String value) {

    public static final int MAX_LENGTH = 128;

    public EventType {
        if (value == null || value.isBlank()) {
            throw new InvalidEventAttributeException("type", "must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidEventAttributeException("type", "must have at most " + MAX_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
