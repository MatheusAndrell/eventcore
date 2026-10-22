package com.eventcore.outbox.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface MessagePublisher {

    Map<UUID, PublishOutcome> publish(List<PendingMessage> messages, Duration confirmTimeout);
}
