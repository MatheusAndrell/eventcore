package com.eventcore.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeliveryAttemptTest {

    private static final Instant STARTED_AT = Instant.parse("2026-10-20T12:00:00Z");

    @ParameterizedTest
    @ValueSource(ints = {200, 202, 204, 299})
    void treatsEverySuccessfulStatusAsDelivered(int status) {
        DeliveryAttempt attempt = attempt(new TransportResult.Responded(status, Optional.empty()));

        assertThat(attempt.succeeded()).isTrue();
        assertThat(attempt.httpStatus()).contains(status);
        assertThat(attempt.error()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 199, 301, 302, 400, 410, 429, 500, 503})
    void treatsEveryOtherStatusAsFailed(int status) {
        DeliveryAttempt attempt = attempt(new TransportResult.Responded(status, Optional.empty()));

        assertThat(attempt.succeeded()).isFalse();
        assertThat(attempt.httpStatus()).contains(status);
        assertThat(attempt.error()).contains("HTTP " + status);
    }

    @Test
    void recordsTransportFailuresWithoutStatusAndTruncatesTheReason() {
        DeliveryAttempt attempt = attempt(new TransportResult.Failed("x".repeat(600)));

        assertThat(attempt.succeeded()).isFalse();
        assertThat(attempt.httpStatus()).isEmpty();
        assertThat(attempt.error()).hasValueSatisfying(error -> assertThat(error).hasSize(512));
        assertThat(attempt.attemptNumber()).isEqualTo(3);
        assertThat(attempt.duration()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void rejectsImpossibleStatusCodes() {
        assertThatThrownBy(() -> new TransportResult.Responded(99, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransportResult.Responded(600, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresTheLeaseToOutliveTwoRequestDeadlines() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(15), Duration.ofSeconds(29)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease must be at least twice request-timeout");
        assertThat(properties(Duration.ofSeconds(15), Duration.ofSeconds(30)).configuredSslBundle()).isEmpty();
    }

    private static DeliveryProperties properties(Duration requestTimeout, Duration lease) {
        return new DeliveryProperties(Duration.ofSeconds(5), Duration.ofSeconds(10), requestTimeout, lease, 50, 5, 10,
                Duration.ofSeconds(30), Duration.ofHours(1), " ");
    }

    private static DeliveryAttempt attempt(TransportResult result) {
        return DeliveryAttempt.of(UUID.randomUUID(), 3, STARTED_AT, Duration.ofMillis(250), result);
    }
}
