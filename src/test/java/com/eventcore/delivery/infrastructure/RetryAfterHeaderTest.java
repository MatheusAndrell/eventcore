package com.eventcore.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetryAfterHeaderTest {

    private static final Instant NOW = Instant.parse("2026-10-21T07:28:00Z");

    @Test
    void readsDelaysInSeconds() {
        assertThat(RetryAfterHeader.parse(new BasicHeader("Retry-After", " 120 "), NOW))
                .contains(Duration.ofSeconds(120));
        assertThat(RetryAfterHeader.parse(new BasicHeader("Retry-After", "0"), NOW)).contains(Duration.ZERO);
    }

    @Test
    void readsHttpDatesRelativeToNow() {
        assertThat(RetryAfterHeader.parse(new BasicHeader("Retry-After", "Wed, 21 Oct 2026 07:30:00 GMT"), NOW))
                .contains(Duration.ofMinutes(2));
        assertThat(RetryAfterHeader.parse(new BasicHeader("Retry-After", "Wed, 21 Oct 2026 07:00:00 GMT"), NOW))
                .contains(Duration.ZERO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-5", "1.5", "soon", "9999999999", "2026-10-21T07:30:00Z"})
    void ignoresValuesThatAreNotValidRetryAfterHeaders(String value) {
        assertThat(RetryAfterHeader.parse(new BasicHeader("Retry-After", value), NOW)).isEmpty();
    }

    @Test
    void ignoresAMissingHeader() {
        assertThat(RetryAfterHeader.parse(null, NOW)).isEmpty();
    }
}
