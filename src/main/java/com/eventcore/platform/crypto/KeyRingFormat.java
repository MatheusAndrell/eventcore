package com.eventcore.platform.crypto;

import java.util.Objects;

public record KeyRingFormat(
        String entriesProperty, String activeProperty, String algorithm, int minKeyBytes, int maxKeyBytes) {

    public KeyRingFormat {
        Objects.requireNonNull(entriesProperty, "entriesProperty");
        Objects.requireNonNull(activeProperty, "activeProperty");
        Objects.requireNonNull(algorithm, "algorithm");
        if (minKeyBytes < 1 || maxKeyBytes < minKeyBytes) {
            throw new IllegalArgumentException("key length bounds must satisfy 1 <= min <= max");
        }
    }

    boolean acceptsLength(int bytes) {
        return bytes >= minKeyBytes && bytes <= maxKeyBytes;
    }

    String lengthRequirement() {
        return minKeyBytes == maxKeyBytes
                ? "exactly " + minKeyBytes + " bytes"
                : maxKeyBytes == Integer.MAX_VALUE
                        ? "at least " + minKeyBytes + " bytes"
                        : "between " + minKeyBytes + " and " + maxKeyBytes + " bytes";
    }
}
