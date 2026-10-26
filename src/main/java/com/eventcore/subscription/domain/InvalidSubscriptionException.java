package com.eventcore.subscription.domain;

public class InvalidSubscriptionException extends RuntimeException {

    public InvalidSubscriptionException(String field, String message) {
        super(field + ": " + message);
    }
}
