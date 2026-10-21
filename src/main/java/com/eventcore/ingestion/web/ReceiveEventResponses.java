package com.eventcore.ingestion.web;

import com.eventcore.ingestion.application.EventReceipt;
import com.eventcore.ingestion.application.ReceiveEventResult;
import com.eventcore.ingestion.web.EventRepresentations.ReceiptResponse;
import com.eventcore.platform.web.Problems;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;

final class ReceiveEventResponses {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private ReceiveEventResponses() {
    }

    static ResponseEntity<ReceiptResponse> toResponse(ReceiveEventResult result) {
        return switch (result) {
            case ReceiveEventResult.Accepted accepted -> created(accepted.receipt())
                    .body(ReceiptResponse.of(accepted.receipt()));
            case ReceiveEventResult.Replayed replayed -> created(replayed.receipt())
                    .header(REPLAYED_HEADER, "true")
                    .body(ReceiptResponse.of(replayed.receipt()));
            case ReceiveEventResult.Rejected rejected -> throw rejection(rejected);
            case ReceiveEventResult.IdempotencyKeyReused _ -> throw Problems.exception(
                    HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused", "Idempotency key reused",
                    "the idempotency key was already used with a different request");
            case ReceiveEventResult.ConcurrentRequestInProgress _ -> throw inProgress();
        };
    }

    private static ResponseEntity.BodyBuilder created(EventReceipt receipt) {
        return ResponseEntity.created(URI.create("/v1/events/" + receipt.id()));
    }

    private static ErrorResponseException rejection(ReceiveEventResult.Rejected rejected) {
        return switch (rejected.reason()) {
            case MALFORMED_ENVELOPE -> Problems.exception(HttpStatus.BAD_REQUEST, "malformed-envelope",
                    "Malformed event envelope", "the event envelope is invalid", rejected.violations());
            case INVALID_STRING -> Problems.exception(HttpStatus.BAD_REQUEST, "invalid-string",
                    "Invalid string", "a string contains an unpaired surrogate", rejected.violations());
            case NUMBER_NOT_REPRESENTABLE -> Problems.exception(HttpStatus.UNPROCESSABLE_CONTENT,
                    "number-not-representable", "Number not representable",
                    "a number cannot be represented without loss; send it as a string", rejected.violations());
            case UNKNOWN_EVENT_TYPE -> Problems.exception(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-event-type",
                    "Unknown event type", "the event type is not registered", rejected.violations());
            case UNSUPPORTED_EVENT_VERSION -> Problems.exception(HttpStatus.UNPROCESSABLE_CONTENT,
                    "unsupported-event-version", "Unsupported event version",
                    "the event version is not accepted", rejected.violations());
            case INVALID_EVENT_DATA -> Problems.exception(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-event-data",
                    "Invalid event data", "the event data does not match its schema", rejected.violations());
            case OCCURRED_IN_FUTURE -> Problems.exception(HttpStatus.UNPROCESSABLE_CONTENT, "occurred-in-future",
                    "Event time in the future", "the event time is beyond the accepted clock skew",
                    rejected.violations());
        };
    }

    private static ErrorResponseException inProgress() {
        ErrorResponseException exception = Problems.exception(HttpStatus.CONFLICT, "idempotency-request-in-progress",
                "Request in progress", "another request with the same idempotency key is still being processed");
        exception.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        return exception;
    }
}
