package com.eventcore.outbox.application;

public interface Outbox {

    void enqueue(OutboxMessage message);
}
