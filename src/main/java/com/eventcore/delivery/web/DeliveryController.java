package com.eventcore.delivery.web;

import com.eventcore.access.application.AuthenticatedClient;
import com.eventcore.delivery.application.DeliveryQueries;
import com.eventcore.delivery.application.RetryDelivery;
import com.eventcore.delivery.application.RetryOutcome;
import com.eventcore.delivery.web.DeliveryRepresentations.DeliveryDetailsResponse;
import com.eventcore.delivery.web.DeliveryRepresentations.DeliveryResponse;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import com.eventcore.platform.web.Problems;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnRole(Role.API)
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class DeliveryController {

    private final DeliveryQueries queries;
    private final RetryDelivery retryDelivery;

    DeliveryController(DeliveryQueries queries, RetryDelivery retryDelivery) {
        this.queries = queries;
        this.retryDelivery = retryDelivery;
    }

    @GetMapping("/events/{eventId}/deliveries")
    ResponseEntity<List<DeliveryResponse>> eventDeliveries(
            @AuthenticationPrincipal AuthenticatedClient client, @PathVariable UUID eventId) {
        return ResponseEntity.of(queries.forEvent(client.clientId(), eventId)
                .map(deliveries -> deliveries.stream().map(DeliveryResponse::of).toList()));
    }

    @GetMapping("/deliveries/{deliveryId}")
    ResponseEntity<DeliveryDetailsResponse> delivery(
            @AuthenticationPrincipal AuthenticatedClient client, @PathVariable UUID deliveryId) {
        return ResponseEntity.of(queries.find(client.clientId(), deliveryId).map(DeliveryDetailsResponse::of));
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    ResponseEntity<DeliveryDetailsResponse> retry(
            @AuthenticationPrincipal AuthenticatedClient client, @PathVariable UUID deliveryId) {
        return switch (retryDelivery.retry(client.clientId(), deliveryId)) {
            case RetryOutcome.Scheduled scheduled ->
                    ResponseEntity.accepted().body(DeliveryDetailsResponse.of(scheduled.details()));
            case RetryOutcome.NotRetriable notRetriable -> throw Problems.exception(HttpStatus.CONFLICT,
                    "delivery-not-retriable", "Delivery not retriable",
                    "only FAILED or CANCELLED deliveries can be retried; this delivery is " + notRetriable.status());
            case RetryOutcome.NotFound _ -> ResponseEntity.notFound().build();
        };
    }
}
