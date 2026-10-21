package com.eventcore.platform.identity;

import java.util.Optional;
import java.util.regex.Pattern;

public record CorrelationId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public CorrelationId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("correlation id must have 1 to 64 characters from [A-Za-z0-9._:-]");
        }
    }

    public static Optional<CorrelationId> parse(String value) {
        return value != null && FORMAT.matcher(value).matches()
                ? Optional.of(new CorrelationId(value))
                : Optional.empty();
    }

    @Override
    public String toString() {
        return value;
    }
}
