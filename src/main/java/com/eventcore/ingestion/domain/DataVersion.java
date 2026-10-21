package com.eventcore.ingestion.domain;

import java.util.regex.Pattern;

public record DataVersion(int value) {

    private static final Pattern TEXT = Pattern.compile("[1-9][0-9]{0,8}");

    public DataVersion {
        if (value < 1) {
            throw new InvalidEventAttributeException("dataversion", "must be a positive integer");
        }
    }

    public static DataVersion parse(String text) {
        if (text == null || !TEXT.matcher(text).matches()) {
            throw new InvalidEventAttributeException("dataversion",
                    "must be a positive integer without leading zeros, written as a string");
        }
        return new DataVersion(Integer.parseInt(text));
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
