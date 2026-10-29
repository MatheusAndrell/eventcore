package com.eventcore.delivery.application;

import com.eventcore.delivery.domain.DeliveryStatus;
import java.util.Objects;

public sealed interface RetryOutcome {

    record Scheduled(DeliveryDetails details) implements RetryOutcome {

        public Scheduled {
            Objects.requireNonNull(details, "details");
        }
    }

    record NotRetriable(DeliveryStatus status) implements RetryOutcome {

        public NotRetriable {
            Objects.requireNonNull(status, "status");
        }
    }

    record NotFound() implements RetryOutcome {
    }
}
