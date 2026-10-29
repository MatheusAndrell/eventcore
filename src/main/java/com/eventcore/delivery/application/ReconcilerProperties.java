package com.eventcore.delivery.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("eventcore.reconciler")
public record ReconcilerProperties(
        @DefaultValue("1m") @NotNull @DurationMin(millis = 100) Duration interval,
        @DefaultValue("5m") @NotNull @DurationMin(millis = 100) Duration staleAfter,
        @DefaultValue("500") @Min(1) @Max(10_000) int batchSize) {
}
