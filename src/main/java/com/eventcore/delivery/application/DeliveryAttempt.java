package com.eventcore.delivery.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record DeliveryAttempt(
        UUID id,
        int attemptNumber,
        Instant startedAt,
        Duration duration,
        Optional<Integer> httpStatus,
        Optional<String> error,
        boolean succeeded) {

    public static final int MAX_ERROR_LENGTH = 512;

    public DeliveryAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(error, "error");
        error = error.map(message -> message.length() > MAX_ERROR_LENGTH
                ? message.substring(0, MAX_ERROR_LENGTH)
                : message);
    }

    static DeliveryAttempt of(UUID id, int attemptNumber, Instant startedAt, Duration duration, TransportResult result) {
        return switch (result) {
            case TransportResult.Responded responded -> new DeliveryAttempt(id, attemptNumber, startedAt, duration,
                    Optional.of(responded.statusCode()),
                    responded.successful() ? Optional.empty() : Optional.of("HTTP " + responded.statusCode()),
                    responded.successful());
            case TransportResult.Failed failed -> new DeliveryAttempt(id, attemptNumber, startedAt, duration,
                    Optional.empty(), Optional.of(failed.reason()), false);
        };
    }
}
