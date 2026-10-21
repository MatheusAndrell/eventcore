package com.eventcore.ingestion.web;

import com.eventcore.ingestion.application.EventCursor;
import com.eventcore.ingestion.domain.EventId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EventCursorCodec {

    private static final String VERSION = "v1";
    private static final Pattern DECODED = Pattern.compile(
            "v1:(-?\\d{1,19}):([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private static final int MAX_ENCODED_LENGTH = 128;

    private EventCursorCodec() {
    }

    static String encode(EventCursor cursor) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, cursor.receivedAt());
        String decoded = VERSION + ":" + micros + ":" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.getBytes(StandardCharsets.US_ASCII));
    }

    static Optional<EventCursor> decode(String encoded) {
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            return Optional.empty();
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        Matcher matcher = DECODED.matcher(decoded);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            Instant receivedAt = Instant.EPOCH.plus(Long.parseLong(matcher.group(1)), ChronoUnit.MICROS);
            return Optional.of(new EventCursor(receivedAt, new EventId(UUID.fromString(matcher.group(2)))));
        } catch (NumberFormatException | ArithmeticException exception) {
            return Optional.empty();
        }
    }
}
