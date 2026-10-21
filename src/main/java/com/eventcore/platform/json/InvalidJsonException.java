package com.eventcore.platform.json;

public class InvalidJsonException extends RuntimeException {

    public InvalidJsonException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidJsonException(String message) {
        super(message);
    }
}
