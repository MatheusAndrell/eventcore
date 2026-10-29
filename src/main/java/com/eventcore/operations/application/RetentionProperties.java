package com.eventcore.operations.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("eventcore.retention")
public record RetentionProperties(
        @DefaultValue("1h") @NotNull @DurationMin(seconds = 1) Duration interval,
        @DefaultValue("30d") @NotNull @DurationMin(days = 1) Duration events,
        @DefaultValue("7d") @NotNull @DurationMin(hours = 1) Duration publishedMessages,
        @DefaultValue("1000") @Min(1) @Max(10_000) int batchSize,
        @DefaultValue("100") @Min(1) @Max(10_000) int maxBatchesPerRun) {
}
