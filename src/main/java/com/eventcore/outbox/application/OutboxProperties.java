package com.eventcore.outbox.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("eventcore.outbox")
public record OutboxProperties(
        @DefaultValue("100") @Min(1) @Max(1000) int batchSize,
        @DefaultValue("200ms") @NotNull @DurationMin(millis = 10) Duration pollInterval,
        @DefaultValue("30s") @NotNull @DurationMin(seconds = 1) Duration lease,
        @DefaultValue("5s") @NotNull @DurationMin(millis = 100) Duration confirmTimeout,
        @DefaultValue("1s") @NotNull @DurationMin(millis = 10) Duration initialRetryDelay,
        @DefaultValue("5m") @NotNull @DurationMin(millis = 10) Duration maxRetryDelay) {

    public OutboxProperties {
        if (lease != null && confirmTimeout != null && lease.compareTo(confirmTimeout.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("eventcore.outbox.lease must be at least twice confirm-timeout");
        }
        if (initialRetryDelay != null && maxRetryDelay != null && initialRetryDelay.compareTo(maxRetryDelay) > 0) {
            throw new IllegalArgumentException(
                    "eventcore.outbox.initial-retry-delay must not exceed max-retry-delay");
        }
    }

    Duration retryDelay(int previousAttempts) {
        int exponent = Math.min(previousAttempts, 30);
        Duration delay = initialRetryDelay.multipliedBy(1L << exponent);
        return delay.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : delay;
    }
}
