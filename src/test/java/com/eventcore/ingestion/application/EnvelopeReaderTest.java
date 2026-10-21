package com.eventcore.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventcore.ingestion.domain.EventSubject;
import com.eventcore.ingestion.domain.EventSubmission;
import com.eventcore.platform.json.JsonCanonicalizer;
import com.eventcore.platform.json.StrictJsonReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

class EnvelopeReaderTest {

    private final StrictJsonReader jsonReader = new StrictJsonReader();
    private final EnvelopeReader reader = new EnvelopeReader(new JsonCanonicalizer());

    @Test
    void readsACompleteEnvelope() {
        EventSubmission submission = reader.read(json("""
                {
                  "type": "order.placed",
                  "dataversion": "2",
                  "time": "2026-10-20T09:00:00-03:00",
                  "source": "urn:acme:orders",
                  "subject": "order/1",
                  "data": {"b": 1, "a": 2}
                }
                """));

        assertThat(submission.type().value()).isEqualTo("order.placed");
        assertThat(submission.dataVersion().value()).isEqualTo(2);
        assertThat(submission.occurredAt()).isEqualTo(Instant.parse("2026-10-20T12:00:00Z"));
        assertThat(submission.source().value()).isEqualTo("urn:acme:orders");
        assertThat(submission.subject()).map(EventSubject::value).contains("order/1");
        assertThat(submission.data().json()).isEqualTo("{\"a\":2,\"b\":1}");
    }

    @Test
    void treatsTheSubjectAsOptional() {
        EventSubmission submission = reader.read(json("""
                {"type": "order.placed", "dataversion": "1", "time": "2026-10-20T12:00:00Z",
                 "source": "urn:acme:orders", "data": {}}
                """));

        assertThat(submission.subject()).isEmpty();
    }

    @Test
    void collectsEveryViolation() {
        assertThatThrownBy(() -> reader.read(json("""
                {"type": 1, "dataversion": "01", "time": "yesterday", "subject": null,
                 "data": [], "extra": true, "another": 1}
                """)))
                .isInstanceOfSatisfying(MalformedEnvelopeException.class, exception -> assertThat(
                        exception.violations()).containsExactly(
                        "another: unknown attribute",
                        "extra: unknown attribute",
                        "type: must be a string",
                        "dataversion: must be a positive integer without leading zeros, written as a string",
                        "time: must be an RFC 3339 date-time with a UTC offset",
                        "source: is required",
                        "subject: must be a string",
                        "data: must be a JSON object"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2026-10-20T12:00:00",
            "2026-10-20 12:00:00Z",
            "2026-10-20",
            "20261020T120000Z"
    })
    void requiresAnRfc3339TimeWithOffset(String time) {
        assertThatThrownBy(() -> reader.read(json("""
                {"type": "order.placed", "dataversion": "1", "time": "%s", "source": "urn:a", "data": {}}
                """.formatted(time))))
                .isInstanceOfSatisfying(MalformedEnvelopeException.class, exception -> assertThat(
                        exception.violations()).isEqualTo(
                        List.of("time: must be an RFC 3339 date-time with a UTC offset")));
    }

    @Test
    void requiresAnObjectBody() {
        assertThatThrownBy(() -> reader.read(json("[]")))
                .isInstanceOfSatisfying(MalformedEnvelopeException.class, exception -> assertThat(
                        exception.violations()).containsExactly("request body must be a JSON object"));
    }

    private JsonNode json(String text) {
        return jsonReader.read(text.getBytes(StandardCharsets.UTF_8));
    }
}
