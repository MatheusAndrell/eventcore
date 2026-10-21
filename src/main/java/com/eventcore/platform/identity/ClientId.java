package com.eventcore.platform.identity;

import java.util.Objects;
import java.util.UUID;

public record ClientId(UUID value) {

    public ClientId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
