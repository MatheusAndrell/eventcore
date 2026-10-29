package com.eventcore.delivery.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public sealed interface TransportResult {

    record Responded(int statusCode, Optional<Duration> retryAfter) implements TransportResult {

        public Responded {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("HTTP status code out of range: " + statusCode);
            }
            Objects.requireNonNull(retryAfter, "retryAfter");
        }

        public boolean successful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    record Failed(String reason) implements TransportResult {

        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
