package com.eventcore.ingestion.domain;

public record EventData(String json) {

    public EventData {
        if (json == null || !json.startsWith("{") || !json.endsWith("}")) {
            throw new InvalidEventAttributeException("data", "must be a JSON object");
        }
    }
}
