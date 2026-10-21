package com.eventcore.ingestion.application;

public class IdempotencyKeyLockedException extends RuntimeException {

    public IdempotencyKeyLockedException(Throwable cause) {
        super("idempotency key is held by a concurrent request", cause);
    }
}
