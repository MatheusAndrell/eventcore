package com.eventcore.subscription.application;

import java.util.List;

public class UnknownEventTypesException extends RuntimeException {

    private final List<String> types;

    UnknownEventTypesException(List<String> types) {
        super("event types are not registered in the event catalog: " + types);
        this.types = List.copyOf(types);
    }

    public List<String> types() {
        return types;
    }
}
