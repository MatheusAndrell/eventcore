package com.eventcore.ingestion.domain;

import java.util.Arrays;
import java.util.HexFormat;

public final class RequestFingerprint {

    public static final int LENGTH = 32;

    private final byte[] value;

    public RequestFingerprint(byte[] value) {
        if (value == null || value.length != LENGTH) {
            throw new IllegalArgumentException("fingerprint must have exactly " + LENGTH + " bytes");
        }
        this.value = value.clone();
    }

    public byte[] bytes() {
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RequestFingerprint fingerprint && Arrays.equals(value, fingerprint.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return HexFormat.of().formatHex(value);
    }
}
