package com.eventcore.delivery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(30), Duration.ofHours(1));

    @ParameterizedTest(name = "after {0} failures the delay is {1}s")
    @CsvSource({"1, 30", "2, 60", "3, 120", "4, 240", "7, 1920", "8, 3600", "50, 3600"})
    void doublesTheDelayUntilTheCap(int failedAttempts, long expectedSeconds) {
        assertThat(policy.delayAfter(failedAttempts, Optional.empty(), 0.5))
                .isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @Test
    void spreadsRetriesWithinTwentyPercentWithoutExceedingTheCap() {
        assertThat(policy.delayAfter(2, Optional.empty(), 0.0)).isEqualTo(Duration.ofSeconds(48));
        assertThat(policy.delayAfter(2, Optional.empty(), 0.999_999)).isEqualTo(Duration.ofMillis(72_000));
        assertThat(policy.delayAfter(20, Optional.empty(), 0.999_999)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void honoursLongerRetryAfterRequestsUpToTheCap() {
        assertThat(policy.delayAfter(1, Optional.of(Duration.ofMinutes(10)), 0.5))
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.delayAfter(1, Optional.of(Duration.ofSeconds(1)), 0.5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delayAfter(1, Optional.of(Duration.ofDays(2)), 0.5)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> policy.delayAfter(0, Optional.empty(), 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.delayAfter(1, Optional.empty(), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ZERO, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ofHours(2), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
