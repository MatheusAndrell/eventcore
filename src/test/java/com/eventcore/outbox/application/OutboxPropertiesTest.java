package com.eventcore.outbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxPropertiesTest {

    @Test
    void doublesTheRetryDelayUpToTheMaximum() {
        OutboxProperties properties = properties(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1),
                Duration.ofSeconds(10));

        assertThat(properties.retryDelay(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.retryDelay(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.retryDelay(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(properties.retryDelay(4)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.retryDelay(1_000)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void requiresTheLeaseToOutliveTwoConfirmationTimeouts() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(9), Duration.ofSeconds(5), Duration.ofSeconds(1),
                Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease must be at least twice confirm-timeout");
        assertThat(properties(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(1),
                Duration.ofSeconds(10)).lease()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void requiresTheInitialRetryDelayNotToExceedTheMaximum() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(20),
                Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial-retry-delay");
    }

    private static OutboxProperties properties(
            Duration lease, Duration confirmTimeout, Duration initialRetryDelay, Duration maxRetryDelay) {
        return new OutboxProperties(100, Duration.ofMillis(200), lease, confirmTimeout, initialRetryDelay,
                maxRetryDelay);
    }
}
