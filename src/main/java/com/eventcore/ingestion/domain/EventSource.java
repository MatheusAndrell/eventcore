package com.eventcore.ingestion.domain;

import java.net.URI;
import java.net.URISyntaxException;

public record EventSource(String value) {

    public static final int MAX_LENGTH = 256;

    public EventSource {
        if (value == null || value.isBlank()) {
            throw new InvalidEventAttributeException("source", "must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidEventAttributeException("source", "must have at most " + MAX_LENGTH + " characters");
        }
        requireUriReference(value);
    }

    private static void requireUriReference(String value) {
        try {
            new URI(value);
        } catch (URISyntaxException exception) {
            throw new InvalidEventAttributeException("source", "must be a URI reference");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
