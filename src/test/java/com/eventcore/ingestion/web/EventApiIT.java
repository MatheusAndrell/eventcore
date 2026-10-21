package com.eventcore.ingestion.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventcore.access.domain.Scope;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.support.ApiIntegrationTest;
import java.io.ByteArrayInputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class EventApiIT extends ApiIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private ClientId client;
    private String writer;
    private String reader;

    @BeforeEach
    void createClient() {
        client = createClient("acme");
        writer = issueKey(client, Scope.EVENTS_WRITE, Scope.EVENTS_READ);
        reader = issueKey(client, Scope.EVENTS_READ);
    }

    @Test
    void acceptsAnEventAndServesItBack() {
        HttpResponse<String> created = send(post("key-1", orderPlaced("o-1")).header("X-Correlation-Id", "corr-abc"));

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("X-Correlation-Id")).contains("corr-abc");
        JsonNode receipt = json(created);
        String id = receipt.get("id").stringValue();
        assertThat(created.headers().firstValue("Location")).contains("/v1/events/" + id);
        assertThat(receipt.get("type").stringValue()).isEqualTo("order.placed");
        assertThat(receipt.get("dataversion").stringValue()).isEqualTo("2");
        assertThat(receipt.get("status").stringValue()).isEqualTo("RECEIVED");

        HttpResponse<String> fetched = send(get("/v1/events/" + id, reader));

        assertThat(fetched.statusCode()).isEqualTo(200);
        JsonNode event = json(fetched);
        assertThat(event.get("correlationId").stringValue()).isEqualTo("corr-abc");
        assertThat(event.get("subject").stringValue()).isEqualTo("order/o-1");
        assertThat(event.get("time").stringValue()).isEqualTo("2026-10-20T12:00:00Z");
        assertThat(event.get("receivedAt").stringValue()).isEqualTo(receipt.get("receivedAt").stringValue());
        assertThat(event.get("data").get("total").get("amount").stringValue()).isEqualTo("15.00");
    }

    @Test
    void replaysAnEquivalentRequest() {
        HttpResponse<String> first = send(post("key-1", orderPlaced("o-1")));
        HttpResponse<String> second = send(post("key-1", orderPlaced("o-1").replace("\n", " ")));

        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(first.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(json(second)).isEqualTo(json(first));
    }

    @Test
    void rejectsAReusedKeyWithDifferentContent() {
        send(post("key-1", orderPlaced("o-1")));

        HttpResponse<String> reused = send(post("key-1", orderPlaced("o-2")).header("X-Correlation-Id", "corr-x"));

        assertProblem(reused, 422, "idempotency-key-reused");
        assertThat(json(reused).get("correlationId").stringValue()).isEqualTo("corr-x");
    }

    @Test
    void reportsAConcurrentRequestWithRetryAfter() throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement("""
                    insert into idempotency_record (
                        client_id, idempotency_key, request_fingerprint, event_id, created_at, expires_at)
                    values (?, 'key-1', decode(repeat('00', 32), 'hex'), gen_random_uuid(), now(),
                            now() + interval '1 day')
                    """)) {
                statement.setObject(1, client.value());
                statement.executeUpdate();
            }

            HttpResponse<String> response = send(post("key-1", orderPlaced("o-1")));

            assertProblem(response, 409, "idempotency-request-in-progress");
            assertThat(response.headers().firstValue("Retry-After")).contains("1");
            holder.rollback();
        }
    }

    @Test
    void requiresAValidIdempotencyKey() {
        assertProblem(send(request("/v1/events").header("Authorization", bearer(writer))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(orderPlaced("o-1")))), 400, "idempotency-key-required");
        assertProblem(send(post("not a key", orderPlaced("o-1"))), 400, "invalid-idempotency-key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"type\": \"a\", \"type\": \"b\"}", "{} {}", "[1,]"})
    void rejectsMalformedJson(String body) {
        assertProblem(send(post("key-1", body)), 400, "malformed-json");
    }

    @Test
    void reportsEnvelopeAndCatalogViolations() {
        HttpResponse<String> malformed = send(post("key-1", "{\"type\": \"order.placed\", \"extra\": 1}"));
        HttpResponse<String> unknownType = send(post("key-2", """
                {"type": "order.cancelled", "dataversion": "1", "time": "2026-10-20T12:00:00Z",
                 "source": "urn:a", "data": {}}
                """));
        HttpResponse<String> invalidData = send(post("key-3", """
                {"type": "payment.captured", "dataversion": "1", "time": "2026-10-20T12:00:00Z",
                 "source": "urn:a", "data": {"paymentId": "p"}}
                """));

        assertProblem(malformed, 400, "malformed-envelope");
        assertThat(json(malformed).get("violations").values()).extracting(JsonNode::stringValue)
                .contains("extra: unknown attribute", "source: is required", "data: is required");
        assertProblem(unknownType, 422, "unknown-event-type");
        assertProblem(invalidData, 422, "invalid-event-data");
        assertThat(json(invalidData).get("violations").size()).isPositive();
    }

    @Test
    void acceptsOnlyUtf8Json() {
        HttpResponse<String> textPlain = send(post("key-1", orderPlaced("o-1"))
                .setHeader("Content-Type", "text/plain"));
        HttpResponse<String> latin1 = send(post("key-2", orderPlaced("o-1"))
                .setHeader("Content-Type", "application/json; charset=ISO-8859-1"));
        HttpResponse<String> utf8 = send(post("key-3", orderPlaced("o-1"))
                .setHeader("Content-Type", "application/json; charset=UTF-8"));

        assertThat(textPlain.statusCode()).isEqualTo(415);
        assertThat(latin1.statusCode()).isEqualTo(415);
        assertThat(utf8.statusCode()).isEqualTo(201);
    }

    @Test
    void rejectsBodiesAboveTheLimitWithOrWithoutContentLength() {
        String oversized = "{\"padding\": \"" + "x".repeat(300 * 1024) + "\"}";
        byte[] bytes = oversized.getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> declared = send(post("key-1", oversized));
        HttpResponse<String> chunked = send(request("/v1/events")
                .header("Authorization", bearer(writer))
                .header("Idempotency-Key", "key-2")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))));

        assertProblem(declared, 413, "payload-too-large");
        assertProblem(chunked, 413, "payload-too-large");
        assertThat(jdbc.sql("select count(*) from event").query(Long.class).single()).isZero();
    }

    @Test
    void hidesEventsOfOtherClients() {
        String id = json(send(post("key-1", orderPlaced("o-1")))).get("id").stringValue();
        String outsider = issueKey(createClient("globex"), Scope.EVENTS_READ);

        assertThat(send(get("/v1/events/" + id, outsider)).statusCode()).isEqualTo(404);
        assertThat(json(send(get("/v1/events", outsider))).get("items").size()).isZero();
    }

    @Test
    void pagesThroughEventsNewestFirst() {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            ids.add(json(send(post("key-" + index, orderPlaced("o-" + index)))).get("id").stringValue());
        }

        List<String> listed = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String path = "/v1/events?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = json(send(get(path, reader)));
            page.get("items").values().forEach(item -> listed.add(item.get("id").stringValue()));
            cursor = page.has("nextCursor") ? page.get("nextCursor").stringValue() : null;
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(listed).containsExactlyElementsOf(ids.reversed());
    }

    @Test
    void filtersEventsByTypeAndReceptionTime() {
        send(post("key-1", orderPlaced("o-1")));
        send(post("key-2", """
                {"type": "payment.captured", "dataversion": "1", "time": "2026-10-20T12:00:00Z",
                 "source": "urn:acme:payments", "data": {"paymentId": "p-1", "orderId": "o-1",
                 "amount": "15.00", "currency": "BRL"}}
                """));

        JsonNode payments = json(send(get("/v1/events?type=payment.captured", reader)));
        JsonNode future = json(send(get("/v1/events?receivedFrom=2999-01-01T00:00:00Z", reader)));

        assertThat(payments.get("items").values()).extracting(item -> item.get("type").stringValue())
                .containsExactly("payment.captured");
        assertThat(payments.get("items").get(0).has("data")).isFalse();
        assertThat(future.get("items").size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/events?limit=0",
            "/v1/events?limit=101",
            "/v1/events?cursor=not-a-cursor",
            "/v1/events?status=UNKNOWN",
            "/v1/events?receivedFrom=yesterday",
            "/v1/events?receivedFrom=2026-10-21T00:00:00Z&receivedBefore=2026-10-20T00:00:00Z"
    })
    void rejectsInvalidQueryParameters(String path) {
        assertThat(send(get(path, reader)).statusCode()).isEqualTo(400);
    }

    @Test
    void generatesACorrelationIdWhenTheHeaderIsMissingOrInvalid() {
        HttpResponse<String> missing = send(post("key-1", orderPlaced("o-1")));
        HttpResponse<String> invalid = send(post("key-2", orderPlaced("o-2")).header("X-Correlation-Id", "bad id!"));

        assertThat(missing.headers().firstValue("X-Correlation-Id")).hasValueSatisfying(
                value -> assertThat(value).matches("[0-9a-f-]{36}"));
        assertThat(invalid.headers().firstValue("X-Correlation-Id")).hasValueSatisfying(
                value -> assertThat(value).isNotEqualTo("bad id!").matches("[0-9a-f-]{36}"));
    }

    private HttpRequest.Builder post(String idempotencyKey, String body) {
        return request("/v1/events")
                .header("Authorization", bearer(writer))
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder get(String path, String apiKey) {
        return request(path).header("Authorization", bearer(apiKey)).GET();
    }

    private static void assertProblem(HttpResponse<String> response, int status, String slug) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/problem+json"));
        JsonNode problem = json(response);
        assertThat(problem.get("type").stringValue()).isEqualTo("urn:eventcore:problem:" + slug);
        assertThat(problem.get("status").intValue()).isEqualTo(status);
        assertThat(problem.has("correlationId")).isTrue();
    }

    private static String orderPlaced(String orderId) {
        return """
                {
                  "type": "order.placed",
                  "dataversion": "2",
                  "time": "2026-10-20T12:00:00Z",
                  "source": "urn:acme:orders",
                  "subject": "order/%1$s",
                  "data": {
                    "orderId": "%1$s",
                    "customerId": "c-1",
                    "total": {"amount": "15.00", "currency": "BRL"},
                    "items": [{"sku": "sku-1", "quantity": 1, "unitPrice": {"amount": "15.00", "currency": "BRL"}}]
                  }
                }
                """.formatted(orderId);
    }
}
