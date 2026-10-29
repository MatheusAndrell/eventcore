package com.eventcore.operations.application;

public record RetentionResult(int idempotencyRecords, int deliveries, int events, int outboxMessages) {

    public boolean deletedAnything() {
        return idempotencyRecords + deliveries + events + outboxMessages > 0;
    }
}
