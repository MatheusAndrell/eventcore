package com.eventcore.subscription.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("eventcore.subscriptions")
public record SubscriptionProperties(
        @DefaultValue("25") @Min(1) @Max(1000) int maxPerClient,
        @DefaultValue("24h") @NotNull @DurationMin(seconds = 0) @DurationMax(days = 7)
        Duration secretRotationGracePeriod) {
}
