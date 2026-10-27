package com.eventcore.delivery.application;

import java.util.UUID;

public class UnknownEventException extends RuntimeException {

    UnknownEventException(UUID eventId) {
        super("event " + eventId + " does not exist");
    }
}
