package com.eventcore.access.domain;

public class InvalidClientNameException extends RuntimeException {

    public InvalidClientNameException(String message) {
        super(message);
    }
}
