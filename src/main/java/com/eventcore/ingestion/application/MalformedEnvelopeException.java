package com.eventcore.ingestion.application;

import java.util.List;

public class MalformedEnvelopeException extends RuntimeException {

    private final List<String> violations;

    public MalformedEnvelopeException(List<String> violations) {
        super("malformed event envelope: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
