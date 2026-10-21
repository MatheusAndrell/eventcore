package com.eventcore.access.domain;

import java.util.regex.Pattern;

public record ClientName(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    public ClientName {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new InvalidClientNameException(
                    "name must have 1 to 128 characters from [a-z0-9._-] and start with a letter or digit");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
