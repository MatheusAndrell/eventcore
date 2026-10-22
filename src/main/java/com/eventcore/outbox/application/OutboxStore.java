package com.eventcore.outbox.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface OutboxStore {

    List<PendingMessage> claim(UUID claimToken, Duration lease, int batchSize);

    boolean markPublished(UUID messageId, UUID claimToken);

    boolean reschedule(UUID messageId, UUID claimToken, Duration delay, String error);
}
