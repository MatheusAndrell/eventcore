package com.eventcore.ingestion.application;

import java.util.List;

public sealed interface ReceiveEventResult {

    record Accepted(EventReceipt receipt) implements ReceiveEventResult {
    }

    record Replayed(EventReceipt receipt) implements ReceiveEventResult {
    }

    record Rejected(Rejection reason, List<String> violations) implements ReceiveEventResult {

        public Rejected {
            violations = List.copyOf(violations);
        }
    }

    record IdempotencyKeyReused() implements ReceiveEventResult {
    }

    record ConcurrentRequestInProgress() implements ReceiveEventResult {
    }
}
