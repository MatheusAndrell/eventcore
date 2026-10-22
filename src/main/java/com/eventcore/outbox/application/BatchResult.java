package com.eventcore.outbox.application;

public record BatchResult(int claimed, int published, int rescheduled, boolean brokerUnavailable) {

    static BatchResult empty() {
        return new BatchResult(0, 0, 0, false);
    }
}
