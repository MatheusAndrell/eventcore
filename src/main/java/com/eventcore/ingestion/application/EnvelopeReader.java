package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.DataVersion;
import com.eventcore.ingestion.domain.EventData;
import com.eventcore.ingestion.domain.EventSource;
import com.eventcore.ingestion.domain.EventSubject;
import com.eventcore.ingestion.domain.EventSubmission;
import com.eventcore.ingestion.domain.EventType;
import com.eventcore.ingestion.domain.InvalidEventAttributeException;
import com.eventcore.platform.json.JsonCanonicalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class EnvelopeReader {

    private static final Set<String> ATTRIBUTES = Set.of("type", "dataversion", "time", "source", "subject", "data");

    private final JsonCanonicalizer canonicalizer;

    public EnvelopeReader(JsonCanonicalizer canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    public EventSubmission read(JsonNode body) {
        if (!body.isObject()) {
            throw new MalformedEnvelopeException(List.of("request body must be a JSON object"));
        }
        List<String> violations = new ArrayList<>();
        body.propertyNames().stream()
                .filter(name -> !ATTRIBUTES.contains(name))
                .sorted()
                .forEach(name -> violations.add(name + ": unknown attribute"));
        EventType type = required(body, "type", EventType::new, violations);
        DataVersion dataVersion = required(body, "dataversion", DataVersion::parse, violations);
        Instant occurredAt = required(body, "time", EnvelopeReader::parseTime, violations);
        EventSource source = required(body, "source", EventSource::new, violations);
        Optional<EventSubject> subject = optional(body, "subject", EventSubject::new, violations);
        EventData data = data(body.get("data"), violations);
        if (!violations.isEmpty()) {
            throw new MalformedEnvelopeException(violations);
        }
        return new EventSubmission(type, dataVersion, source, subject, occurredAt, data);
    }

    private static <T> T required(JsonNode body, String name, Function<String, T> parser, List<String> violations) {
        JsonNode node = body.get(name);
        if (node == null) {
            violations.add(name + ": is required");
            return null;
        }
        return parse(name, node, parser, violations);
    }

    private static <T> Optional<T> optional(
            JsonNode body, String name, Function<String, T> parser, List<String> violations) {
        JsonNode node = body.get(name);
        return node == null ? Optional.empty() : Optional.ofNullable(parse(name, node, parser, violations));
    }

    private static <T> T parse(String name, JsonNode node, Function<String, T> parser, List<String> violations) {
        if (!node.isString()) {
            violations.add(name + ": must be a string");
            return null;
        }
        try {
            return parser.apply(node.stringValue());
        } catch (InvalidEventAttributeException exception) {
            violations.add(exception.getMessage());
            return null;
        }
    }

    private EventData data(JsonNode node, List<String> violations) {
        if (node == null) {
            violations.add("data: is required");
            return null;
        }
        if (!node.isObject()) {
            violations.add("data: must be a JSON object");
            return null;
        }
        return new EventData(canonicalizer.canonicalText(node));
    }

    private static Instant parseTime(String text) {
        try {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
                    .truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException exception) {
            throw new InvalidEventAttributeException("time", "must be an RFC 3339 date-time with a UTC offset");
        }
    }
}
