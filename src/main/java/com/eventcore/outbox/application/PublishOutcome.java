package com.eventcore.outbox.application;

public sealed interface PublishOutcome {

    record Confirmed() implements PublishOutcome {
    }

    record Nacked(String reason) implements PublishOutcome {
    }

    record Returned(int replyCode, String replyText) implements PublishOutcome {
    }

    record TimedOut() implements PublishOutcome {
    }

    record Failed(String reason) implements PublishOutcome {
    }
}
