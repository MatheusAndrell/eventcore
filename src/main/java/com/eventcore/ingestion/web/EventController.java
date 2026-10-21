package com.eventcore.ingestion.web;

import com.eventcore.access.application.AuthenticatedClient;
import com.eventcore.ingestion.application.EventCriteria;
import com.eventcore.ingestion.application.EventCursor;
import com.eventcore.ingestion.application.EventPage;
import com.eventcore.ingestion.application.EventQueries;
import com.eventcore.ingestion.application.ReceiveEvent;
import com.eventcore.ingestion.application.ReceiveEventCommand;
import com.eventcore.ingestion.domain.EventId;
import com.eventcore.ingestion.domain.EventStatus;
import com.eventcore.ingestion.domain.EventType;
import com.eventcore.ingestion.domain.IdempotencyKey;
import com.eventcore.ingestion.domain.InvalidEventAttributeException;
import com.eventcore.ingestion.web.EventRepresentations.EventPageResponse;
import com.eventcore.ingestion.web.EventRepresentations.EventResponse;
import com.eventcore.ingestion.web.EventRepresentations.EventSummaryResponse;
import com.eventcore.ingestion.web.EventRepresentations.ReceiptResponse;
import com.eventcore.platform.json.StrictJsonReader;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import com.eventcore.platform.web.CorrelationIdFilter;
import com.eventcore.platform.web.MediaTypes;
import com.eventcore.platform.web.Problems;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnRole(Role.API)
@RequestMapping("/v1/events")
class EventController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String DEFAULT_PAGE_SIZE = "20";

    private final ReceiveEvent receiveEvent;
    private final EventQueries queries;
    private final StrictJsonReader jsonReader;

    EventController(ReceiveEvent receiveEvent, EventQueries queries, StrictJsonReader jsonReader) {
        this.receiveEvent = receiveEvent;
        this.queries = queries;
        this.jsonReader = jsonReader;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReceiptResponse> receive(
            @AuthenticationPrincipal AuthenticatedClient client,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            HttpServletRequest request) throws IOException, HttpMediaTypeNotSupportedException {
        MediaTypes.requireUtf8(request);
        IdempotencyKey key = idempotencyKey(idempotencyKey);
        ReceiveEventCommand command = new ReceiveEventCommand(client.clientId(), key,
                CorrelationIdFilter.current(request), jsonReader.read(request.getInputStream().readAllBytes()));
        return ReceiveEventResponses.toResponse(receiveEvent.receive(command));
    }

    @GetMapping(path = "/{eventId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EventResponse> event(
            @AuthenticationPrincipal AuthenticatedClient client, @PathVariable UUID eventId) {
        return ResponseEntity.of(queries.find(client.clientId(), new EventId(eventId)).map(EventResponse::of));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    EventPageResponse events(
            @AuthenticationPrincipal AuthenticatedClient client,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) EventStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant receivedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant receivedBefore,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int limit,
            @RequestParam(required = false) String cursor) {
        if (limit < 1 || limit > EventQueries.MAX_PAGE_SIZE) {
            throw invalidQuery("limit must be between 1 and " + EventQueries.MAX_PAGE_SIZE);
        }
        EventPage page = queries.list(client.clientId(), criteria(type, status, receivedFrom, receivedBefore),
                cursor(cursor), limit);
        return new EventPageResponse(page.items().stream().map(EventSummaryResponse::of).toList(),
                page.next().map(EventCursorCodec::encode).orElse(null));
    }

    private static IdempotencyKey idempotencyKey(String value) {
        if (value == null) {
            throw Problems.exception(HttpStatus.BAD_REQUEST, "idempotency-key-required", "Idempotency key required",
                    "the " + IDEMPOTENCY_KEY_HEADER + " header is required");
        }
        try {
            return new IdempotencyKey(value);
        } catch (InvalidEventAttributeException exception) {
            throw Problems.exception(HttpStatus.BAD_REQUEST, "invalid-idempotency-key", "Invalid idempotency key",
                    exception.getMessage());
        }
    }

    private static EventCriteria criteria(
            String type, EventStatus status, Instant receivedFrom, Instant receivedBefore) {
        try {
            return new EventCriteria(Optional.ofNullable(type).map(EventType::new), Optional.ofNullable(status),
                    Optional.ofNullable(receivedFrom), Optional.ofNullable(receivedBefore));
        } catch (InvalidEventAttributeException | IllegalArgumentException exception) {
            throw invalidQuery(exception.getMessage());
        }
    }

    private static Optional<EventCursor> cursor(String cursor) {
        if (cursor == null) {
            return Optional.empty();
        }
        return Optional.of(EventCursorCodec.decode(cursor).orElseThrow(() -> invalidQuery("cursor is not valid")));
    }

    private static ErrorResponseException invalidQuery(String detail) {
        return Problems.exception(HttpStatus.BAD_REQUEST, "invalid-query-parameter", "Invalid query parameter",
                detail);
    }
}
