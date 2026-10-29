package com.eventcore.delivery.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record RetryPolicy(Duration initialDelay, Duration maxDelay) {

    private static final double JITTER_FLOOR = 0.8;
    private static final double JITTER_SPAN = 0.4;
    private static final int MAX_EXPONENT = 30;

    public RetryPolicy {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (initialDelay.isNegative() || initialDelay.isZero()) {
            throw new IllegalArgumentException("initialDelay must be positive");
        }
        if (initialDelay.compareTo(maxDelay) > 0) {
            throw new IllegalArgumentException("initialDelay must not exceed maxDelay");
        }
    }

    public Duration delayAfter(int failedAttempts, Optional<Duration> retryAfter, double jitterSample) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts must be positive: " + failedAttempts);
        }
        if (jitterSample < 0 || jitterSample >= 1) {
            throw new IllegalArgumentException("jitterSample must be in [0, 1): " + jitterSample);
        }
        Duration exponential = capped(initialDelay.multipliedBy(1L << Math.min(failedAttempts - 1, MAX_EXPONENT)));
        Duration jittered = capped(Duration.ofMillis(
                Math.round(exponential.toMillis() * (JITTER_FLOOR + JITTER_SPAN * jitterSample))));
        return retryAfter.map(this::capped).filter(requested -> requested.compareTo(jittered) > 0).orElse(jittered);
    }

    private Duration capped(Duration delay) {
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
