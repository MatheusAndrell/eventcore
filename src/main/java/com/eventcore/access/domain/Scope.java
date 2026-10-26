package com.eventcore.access.domain;

import java.util.Arrays;
import java.util.Optional;

public enum Scope {
    EVENTS_WRITE("events:write"),
    EVENTS_READ("events:read"),
    SUBSCRIPTIONS_MANAGE("subscriptions:manage"),
    ADMIN("admin");

    private final String value;

    Scope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<Scope> fromValue(String value) {
        return Arrays.stream(values()).filter(scope -> scope.value.equals(value)).findFirst();
    }
}
