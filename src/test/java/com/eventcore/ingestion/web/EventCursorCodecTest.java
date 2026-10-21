package com.eventcore.ingestion.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventcore.ingestion.application.EventCursor;
import com.eventcore.ingestion.domain.EventId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventCursorCodecTest {

    @Test
    void roundTripsCursorsWithMicrosecondPrecision() {
        EventCursor cursor = new EventCursor(Instant.parse("2026-10-20T12:00:00.123456Z"),
                new EventId(UUID.fromString("0192f3c8-6f1b-7c39-9d0e-2b6c1f0a8e11")));

        String encoded = EventCursorCodec.encode(cursor);

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(EventCursorCodec.decode(encoded)).contains(cursor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not base64!", "djE6", "djE6MTIzOm5vdC1hLXV1aWQ"})
    void rejectsMalformedCursors(String encoded) {
        assertThat(EventCursorCodec.decode(encoded)).isEmpty();
    }

    @Test
    void rejectsCursorsWithAnotherVersionOrOverlongInput() {
        String otherVersion = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "v2:1:0192f3c8-6f1b-7c39-9d0e-2b6c1f0a8e11".getBytes(StandardCharsets.US_ASCII));

        assertThat(EventCursorCodec.decode(otherVersion)).isEmpty();
        assertThat(EventCursorCodec.decode("A".repeat(129))).isEmpty();
    }
}
