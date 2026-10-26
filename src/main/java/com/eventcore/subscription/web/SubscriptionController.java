package com.eventcore.subscription.web;

import com.eventcore.access.application.AuthenticatedClient;
import com.eventcore.platform.egress.EgressDeniedException;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import com.eventcore.platform.web.Problems;
import com.eventcore.subscription.application.SubscriptionLimitReachedException;
import com.eventcore.subscription.application.SubscriptionManagement;
import com.eventcore.subscription.application.UnknownEventTypesException;
import com.eventcore.subscription.domain.InvalidSubscriptionException;
import com.eventcore.subscription.domain.SubscribedEventTypes;
import com.eventcore.subscription.domain.SubscriptionChanges;
import com.eventcore.subscription.domain.WebhookEndpoint;
import com.eventcore.subscription.web.SubscriptionRepresentations.ChangeSubscriptionRequest;
import com.eventcore.subscription.web.SubscriptionRepresentations.CreateSubscriptionRequest;
import com.eventcore.subscription.web.SubscriptionRepresentations.CreatedSubscriptionResponse;
import com.eventcore.subscription.web.SubscriptionRepresentations.RotatedSecretResponse;
import com.eventcore.subscription.web.SubscriptionRepresentations.SubscriptionResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnRole(Role.API)
@RequestMapping(path = "/v1/subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
class SubscriptionController {

    private final SubscriptionManagement management;

    SubscriptionController(SubscriptionManagement management) {
        this.management = management;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CreatedSubscriptionResponse> create(
            @AuthenticationPrincipal AuthenticatedClient client,
            @Valid @RequestBody CreateSubscriptionRequest request) {
        WebhookEndpoint endpoint = valid(() -> WebhookEndpoint.parse(request.url()));
        SubscribedEventTypes eventTypes = valid(() -> SubscribedEventTypes.of(request.eventTypes()));
        CreatedSubscriptionResponse created = CreatedSubscriptionResponse.of(
                admitted(() -> management.create(client.clientId(), endpoint, eventTypes)));
        return ResponseEntity.created(URI.create("/v1/subscriptions/" + created.id())).body(created);
    }

    @GetMapping
    List<SubscriptionResponse> subscriptions(@AuthenticationPrincipal AuthenticatedClient client) {
        return management.list(client.clientId()).stream().map(SubscriptionResponse::of).toList();
    }

    @GetMapping("/{subscriptionId}")
    ResponseEntity<SubscriptionResponse> subscription(
            @AuthenticationPrincipal AuthenticatedClient client, @PathVariable UUID subscriptionId) {
        return ResponseEntity.of(management.find(client.clientId(), subscriptionId).map(SubscriptionResponse::of));
    }

    @PatchMapping(path = "/{subscriptionId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SubscriptionResponse> change(
            @AuthenticationPrincipal AuthenticatedClient client,
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody ChangeSubscriptionRequest request) {
        SubscriptionChanges changes = valid(() -> new SubscriptionChanges(
                Optional.ofNullable(request.url()).map(WebhookEndpoint::parse),
                Optional.ofNullable(request.eventTypes()).map(SubscribedEventTypes::of),
                Optional.ofNullable(request.status())));
        return ResponseEntity.of(admitted(() -> management.change(client.clientId(), subscriptionId, changes))
                .map(SubscriptionResponse::of));
    }

    @DeleteMapping("/{subscriptionId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedClient client, @PathVariable UUID subscriptionId) {
        return management.delete(client.clientId(), subscriptionId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/{subscriptionId}/rotate-secret")
    ResponseEntity<RotatedSecretResponse> rotateSecret(
            @AuthenticationPrincipal AuthenticatedClient client, @PathVariable UUID subscriptionId) {
        return ResponseEntity.of(management.rotateSecret(client.clientId(), subscriptionId)
                .map(RotatedSecretResponse::of));
    }

    private static <T> T valid(Supplier<T> parser) {
        try {
            return parser.get();
        } catch (InvalidSubscriptionException exception) {
            throw Problems.exception(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request",
                    "the request body has invalid fields", List.of(exception.getMessage()));
        }
    }

    private static <T> T admitted(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (UnknownEventTypesException exception) {
            throw Problems.exception(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-event-types", "Unknown event types",
                    "every subscribed event type must be registered in the event catalog",
                    exception.types().stream()
                            .map(type -> "eventTypes: '" + type + "' is not registered in the event catalog")
                            .toList());
        } catch (EgressDeniedException exception) {
            throw Problems.exception(HttpStatus.UNPROCESSABLE_CONTENT, "endpoint-not-allowed", "Endpoint not allowed",
                    "the webhook endpoint is not an allowed destination", List.of("url: " + exception.getMessage()));
        } catch (SubscriptionLimitReachedException exception) {
            throw Problems.exception(HttpStatus.CONFLICT, "subscription-limit-reached", "Subscription limit reached",
                    exception.getMessage());
        }
    }
}
