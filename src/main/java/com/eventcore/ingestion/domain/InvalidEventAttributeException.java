package com.eventcore.ingestion.domain;

public class InvalidEventAttributeException extends RuntimeException {

    private final String attribute;

    public InvalidEventAttributeException(String attribute, String reason) {
        super(attribute + ": " + reason);
        this.attribute = attribute;
    }

    public String attribute() {
        return attribute;
    }
}
