package com.eventcore.ingestion.domain;

import java.util.regex.Pattern;

public record IdempotencyKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public IdempotencyKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new InvalidEventAttributeException("Idempotency-Key",
                    "must have 1 to 128 characters from [A-Za-z0-9._:-]");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
