package com.eventcore.ingestion.application;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("eventcore.ingestion")
public record IngestionProperties(
        @DefaultValue("24h") @NotNull @DurationMin(hours = 24) Duration idempotencyRetention,
        @DefaultValue("2s") @NotNull @DurationMin(millis = 100) @DurationMax(seconds = 30) Duration idempotencyLockTimeout,
        @DefaultValue("5m") @NotNull @DurationMax(hours = 1) Duration clockSkewTolerance) {
}
