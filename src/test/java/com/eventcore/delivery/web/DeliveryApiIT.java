package com.eventcore.delivery.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventcore.access.domain.ClientName;
import com.eventcore.access.domain.Scope;
import com.eventcore.support.DeliveryIntegrationTest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class DeliveryApiIT extends DeliveryIntegrationTest {

    @Test
    void exposesTheDeliveriesOfAnEventAndTheirAttempts() {
        subscribe("/api-flaky", "payment.captured");
        receiver.replyInOrder("/api-flaky", 500);
        UUID eventId = receivePayment("payment-api");
        UUID deliveryId = (UUID) awaitDeliveries(eventId, 1).getFirst().get("id");
        String reader = issueKey(Scope.EVENTS_READ);

        HttpResponse<String> listed = get("/v1/events/" + eventId + "/deliveries", reader);
        HttpResponse<String> details = get("/v1/deliveries/" + deliveryId, reader);

        assertThat(listed.statusCode()).isEqualTo(200);
        JsonNode delivery = json(listed).get(0);
        assertThat(delivery.get("id").stringValue()).isEqualTo(deliveryId.toString());
        assertThat(delivery.get("status").stringValue()).isEqualTo("SUCCEEDED");
        assertThat(delivery.get("attempts").intValue()).isEqualTo(2);
        assertThat(delivery.get("maxAttempts").intValue()).isEqualTo(3);
        assertThat(delivery.has("nextAttemptAt")).isFalse();
        assertThat(delivery.has("completedAt")).isTrue();
        assertThat(details.statusCode()).isEqualTo(200);
        JsonNode attempts = json(details).get("attempts");
        assertThat(attempts.size()).isEqualTo(2);
        assertThat(attempts.get(0).get("httpStatus").intValue()).isEqualTo(500);
        assertThat(attempts.get(0).get("error").stringValue()).isEqualTo("HTTP 500");
        assertThat(attempts.get(0).get("succeeded").booleanValue()).isFalse();
        assertThat(attempts.get(1).get("httpStatus").intValue()).isEqualTo(204);
        assertThat(attempts.get(1).get("succeeded").booleanValue()).isTrue();
        assertThat(attempts.get(1).has("error")).isFalse();
    }

    @Test
    void retriesFailedDeliveriesOnRequestWithAFreshAttemptBudget() {
        subscribe("/api-retry", "payment.captured");
        receiver.reply("/api-retry", 503);
        UUID eventId = receivePayment("payment-retry");
        UUID deliveryId = (UUID) awaitDeliveries(eventId, 1).getFirst().get("id");
        String manager = issueKey(Scope.SUBSCRIPTIONS_MANAGE);
        receiver.reply("/api-retry", 204);

        HttpResponse<String> retried = post("/v1/deliveries/" + deliveryId + "/retry", manager);

        assertThat(retried.statusCode()).isEqualTo(202);
        assertThat(json(retried).get("delivery").get("maxAttempts").intValue()).isEqualTo(6);
        await().atMost(DELIVERY_TIMEOUT).until(() -> "SUCCEEDED".equals(deliveries(eventId).getFirst().get("status")));
        assertThat(deliveries(eventId).getFirst().get("attempts")).isEqualTo(4);
        assertThat(attemptStatuses(deliveryId)).containsExactly(503, 503, 503, 204);
        HttpResponse<String> again = post("/v1/deliveries/" + deliveryId + "/retry", manager);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(json(again).get("type").stringValue()).isEqualTo("urn:eventcore:problem:delivery-not-retriable");
    }

    @Test
    void isolatesDeliveriesBetweenClientsAndRequiresScopes() {
        subscribe("/api-isolated", "payment.captured");
        UUID eventId = receivePayment("payment-isolated");
        UUID deliveryId = (UUID) awaitDeliveries(eventId, 1).getFirst().get("id");
        String otherReader = administration.issueApiKey(administration.createClient(new ClientName("globex")).id(),
                Set.of(Scope.EVENTS_READ, Scope.SUBSCRIPTIONS_MANAGE), Optional.empty()).orElseThrow().token().value();
        String writer = issueKey(Scope.EVENTS_WRITE);
        String reader = issueKey(Scope.EVENTS_READ);

        assertThat(get("/v1/events/" + eventId + "/deliveries", otherReader).statusCode()).isEqualTo(404);
        assertThat(get("/v1/deliveries/" + deliveryId, otherReader).statusCode()).isEqualTo(404);
        assertThat(post("/v1/deliveries/" + deliveryId + "/retry", otherReader).statusCode()).isEqualTo(404);
        assertThat(get("/v1/events/" + UUID.randomUUID() + "/deliveries", reader).statusCode()).isEqualTo(404);
        assertThat(get("/v1/deliveries/" + deliveryId, writer).statusCode()).isEqualTo(403);
        assertThat(post("/v1/deliveries/" + deliveryId + "/retry", reader).statusCode()).isEqualTo(403);
    }
}
