package com.eventcore.delivery.application;

import java.util.Objects;

public sealed interface TransportResult {

    record Responded(int statusCode) implements TransportResult {

        public Responded {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("HTTP status code out of range: " + statusCode);
            }
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
