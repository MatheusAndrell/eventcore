package com.eventcore.ingestion.web;

import com.eventcore.ingestion.application.EventReceipt;
import com.eventcore.ingestion.application.EventSummary;
import com.eventcore.ingestion.domain.Event;
import com.eventcore.ingestion.domain.EventSubject;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.List;

final class EventRepresentations {

    private EventRepresentations() {
    }

    record ReceiptResponse(String id, String type, String dataversion, String status, Instant receivedAt) {

        static ReceiptResponse of(EventReceipt receipt) {
            return new ReceiptResponse(receipt.id().toString(), receipt.type().value(),
                    receipt.dataVersion().toString(), receipt.status().name(), receipt.receivedAt());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EventResponse(
            String id,
            String type,
            String dataversion,
            String source,
            String subject,
            Instant time,
            Instant receivedAt,
            String status,
            String correlationId,
            @JsonRawValue String data) {

        static EventResponse of(Event event) {
            return new EventResponse(event.id().toString(), event.type().value(), event.dataVersion().toString(),
                    event.source().value(), event.subject().map(EventSubject::value).orElse(null),
                    event.occurredAt(), event.receivedAt(), event.status().name(), event.correlationId().value(),
                    event.data().json());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EventSummaryResponse(
            String id,
            String type,
            String dataversion,
            String source,
            String subject,
            Instant time,
            Instant receivedAt,
            String status,
            String correlationId) {

        static EventSummaryResponse of(EventSummary summary) {
            return new EventSummaryResponse(summary.id().toString(), summary.type().value(),
                    summary.dataVersion().toString(), summary.source().value(),
                    summary.subject().map(EventSubject::value).orElse(null), summary.occurredAt(),
                    summary.receivedAt(), summary.status().name(), summary.correlationId().value());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EventPageResponse(List<EventSummaryResponse> items, String nextCursor) {
    }
}
