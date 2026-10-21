package com.eventcore.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.identity.CorrelationId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-20T12:00:00Z");
    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final EventId ID = new EventId(UUID.fromString("0192f3c8-6f1b-7c39-9d0e-2b6c1f0a8e11"));
    private static final ClientId CLIENT = new ClientId(UUID.fromString("0192f3c8-0000-7000-8000-000000000001"));
    private static final CorrelationId CORRELATION = new CorrelationId("corr-1");

    @Test
    void receivesASubmissionInTheReceivedState() {
        EventSubmission submission = submissionAt(RECEIVED_AT.minusSeconds(30));

        Event event = Event.receive(ID, CLIENT, CORRELATION, submission, RECEIVED_AT, TOLERANCE);

        assertThat(event.status()).isEqualTo(EventStatus.RECEIVED);
        assertThat(event.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(event.occurredAt()).isEqualTo(submission.occurredAt());
        assertThat(event.type()).isEqualTo(submission.type());
        assertThat(event.data()).isEqualTo(submission.data());
        assertThat(event.correlationId()).isEqualTo(CORRELATION);
    }

    @Test
    void acceptsOccurrencesWithinTheClockSkewTolerance() {
        EventSubmission submission = submissionAt(RECEIVED_AT.plus(TOLERANCE));

        Event event = Event.receive(ID, CLIENT, CORRELATION, submission, RECEIVED_AT, TOLERANCE);

        assertThat(event.occurredAt()).isEqualTo(RECEIVED_AT.plus(TOLERANCE));
    }

    @Test
    void rejectsOccurrencesBeyondTheClockSkewTolerance() {
        EventSubmission submission = submissionAt(RECEIVED_AT.plus(TOLERANCE).plusMillis(1));

        assertThatThrownBy(() -> Event.receive(ID, CLIENT, CORRELATION, submission, RECEIVED_AT, TOLERANCE))
                .isInstanceOf(EventOccursInFutureException.class);
    }

    private static EventSubmission submissionAt(Instant occurredAt) {
        return new EventSubmission(new EventType("order.placed"), new DataVersion(2), new EventSource("urn:acme:orders"),
                Optional.of(new EventSubject("order/1")), occurredAt, new EventData("{\"orderId\":\"o-1\"}"));
    }
}
