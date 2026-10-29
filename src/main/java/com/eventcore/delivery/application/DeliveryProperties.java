package com.eventcore.delivery.application;

import com.eventcore.delivery.domain.RetryPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Optional;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("eventcore.delivery")
public record DeliveryProperties(
        @DefaultValue("5s") @NotNull @DurationMin(millis = 100) @DurationMax(seconds = 30) Duration connectTimeout,
        @DefaultValue("10s") @NotNull @DurationMin(millis = 100) @DurationMax(seconds = 60) Duration responseTimeout,
        @DefaultValue("15s") @NotNull @DurationMin(millis = 100) @DurationMax(seconds = 120) Duration requestTimeout,
        @DefaultValue("1m") @NotNull @DurationMin(seconds = 1) Duration lease,
        @DefaultValue("50") @Min(1) @Max(1000) int maxConnections,
        @DefaultValue("5") @Min(1) @Max(100) int maxConnectionsPerEndpoint,
        @DefaultValue("10") @Min(1) @Max(50) int maxAttempts,
        @DefaultValue("30s") @NotNull @DurationMin(millis = 10) Duration initialRetryDelay,
        @DefaultValue("1h") @NotNull @DurationMin(millis = 10) @DurationMax(hours = 24) Duration maxRetryDelay,
        String sslBundle) {

    public DeliveryProperties {
        if (requestTimeout != null && lease != null && lease.compareTo(requestTimeout.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("eventcore.delivery.lease must be at least twice request-timeout");
        }
        if (initialRetryDelay != null && maxRetryDelay != null && initialRetryDelay.compareTo(maxRetryDelay) > 0) {
            throw new IllegalArgumentException(
                    "eventcore.delivery.initial-retry-delay must not exceed max-retry-delay");
        }
    }

    public RetryPolicy retryPolicy() {
        return new RetryPolicy(initialRetryDelay, maxRetryDelay);
    }

    public Optional<String> configuredSslBundle() {
        return sslBundle == null || sslBundle.isBlank() ? Optional.empty() : Optional.of(sslBundle.strip());
    }
}
