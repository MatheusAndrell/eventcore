package com.eventcore.delivery.web;

import com.eventcore.delivery.application.DeliveryAttempt;
import com.eventcore.delivery.application.DeliveryDetails;
import com.eventcore.delivery.application.DeliverySummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

final class DeliveryRepresentations {

    private DeliveryRepresentations() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DeliveryResponse(
            String id,
            String eventId,
            String subscriptionId,
            String status,
            int attempts,
            int maxAttempts,
            Instant nextAttemptAt,
            String lastError,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {

        static DeliveryResponse of(DeliverySummary delivery) {
            return new DeliveryResponse(delivery.id().toString(), delivery.eventId().toString(),
                    delivery.subscriptionId().toString(), delivery.status().name(), delivery.attempts(),
                    delivery.maxAttempts(), delivery.nextAttemptAt().orElse(null), delivery.lastError().orElse(null),
                    delivery.createdAt(), delivery.updatedAt(), delivery.completedAt().orElse(null));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AttemptResponse(
            int attemptNumber, Instant startedAt, long durationMs, Integer httpStatus, String error, boolean succeeded) {

        static AttemptResponse of(DeliveryAttempt attempt) {
            return new AttemptResponse(attempt.attemptNumber(), attempt.startedAt(), attempt.duration().toMillis(),
                    attempt.httpStatus().orElse(null), attempt.error().orElse(null), attempt.succeeded());
        }
    }

    record DeliveryDetailsResponse(DeliveryResponse delivery, List<AttemptResponse> attempts) {

        static DeliveryDetailsResponse of(DeliveryDetails details) {
            return new DeliveryDetailsResponse(DeliveryResponse.of(details.delivery()),
                    details.attempts().stream().map(AttemptResponse::of).toList());
        }
    }
}
