package com.eventcore.delivery.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.hc.core5.http.Header;

final class RetryAfterHeader {

    private static final Pattern DELAY_SECONDS = Pattern.compile("[0-9]{1,9}");

    private RetryAfterHeader() {
    }

    static Optional<Duration> parse(Header header, Instant now) {
        if (header == null || header.getValue() == null) {
            return Optional.empty();
        }
        String value = header.getValue().strip();
        if (DELAY_SECONDS.matcher(value).matches()) {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
        }
        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Optional.of(retryAt.isAfter(now) ? Duration.between(now, retryAt) : Duration.ZERO);
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }
}
