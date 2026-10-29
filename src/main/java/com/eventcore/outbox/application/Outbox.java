package com.eventcore.outbox.application;

import java.time.Duration;

public interface Outbox {

    void enqueue(OutboxMessage message);

    void enqueueAfter(OutboxMessage message, Duration delay);
}
