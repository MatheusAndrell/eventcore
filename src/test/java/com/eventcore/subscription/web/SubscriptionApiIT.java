package com.eventcore.subscription.web;

import static com.eventcore.support.StaticHostResolverConfiguration.OTHER_PUBLIC_HOST;
import static com.eventcore.support.StaticHostResolverConfiguration.PRIVATE_HOST;
import static com.eventcore.support.StaticHostResolverConfiguration.PUBLIC_HOST;
import static com.eventcore.support.StaticHostResolverConfiguration.REBINDING_HOST;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventcore.access.domain.Scope;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.support.ApiIntegrationTest;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class SubscriptionApiIT extends ApiIntegrationTest {

    private static final byte[] TEST_ENCRYPTION_KEY = "test-webhook-secret-key-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String PUBLIC_URL = "https://" + PUBLIC_HOST + "/webhooks";

    private String apiKey;

    @BeforeEach
    void createSubscriber() {
        apiKey = issueKey(createClient("acme"), Scope.SUBSCRIPTIONS_MANAGE);
    }

    @Test
    void createsASubscriptionAndRevealsItsSecretOnlyOnce() throws GeneralSecurityException {
        HttpResponse<String> created = send(post("/v1/subscriptions",
                body(PUBLIC_URL, "payment.captured", "order.placed")));

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode subscription = json(created);
        String id = subscription.get("id").stringValue();
        String secret = subscription.get("secret").stringValue();
        assertThat(created.headers().firstValue("Location")).contains("/v1/subscriptions/" + id);
        assertThat(subscription.get("url").stringValue()).isEqualTo(PUBLIC_URL);
        assertThat(subscription.get("eventTypes").values()).extracting(JsonNode::stringValue)
                .containsExactly("order.placed", "payment.captured");
        assertThat(subscription.get("status").stringValue()).isEqualTo("ACTIVE");
        assertThat(secret).matches("whsec_[A-Za-z0-9+/]{43}=");

        HttpResponse<String> fetched = send(get("/v1/subscriptions/" + id));
        HttpResponse<String> listed = send(get("/v1/subscriptions"));
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(json(fetched).has("secret")).isFalse();
        assertThat(json(listed).size()).isEqualTo(1);
        assertThat(fetched.body() + listed.body()).doesNotContain(secret.substring(6));

        List<StoredSecret> stored = storedSecrets(id);
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().expiresAt()).isNull();
        assertThat(stored.getFirst().ciphertext()).isNotEqualTo(decodedKey(secret));
        assertThat(decrypt(stored.getFirst())).isEqualTo(decodedKey(secret));
    }

    @Test
    void isolatesSubscriptionsBetweenClients() {
        String id = json(send(post("/v1/subscriptions", body(PUBLIC_URL, "order.placed")))).get("id").stringValue();
        String otherKey = issueKey(createClient("globex"), Scope.SUBSCRIPTIONS_MANAGE);

        assertThat(send(get("/v1/subscriptions/" + id, otherKey)).statusCode()).isEqualTo(404);
        assertThat(json(send(get("/v1/subscriptions", otherKey))).size()).isZero();
        assertThat(send(patch("/v1/subscriptions/" + id, "{\"status\": \"PAUSED\"}", otherKey)).statusCode())
                .isEqualTo(404);
        assertThat(send(post("/v1/subscriptions/" + id + "/rotate-secret", "", otherKey)).statusCode())
                .isEqualTo(404);
        assertThat(send(delete("/v1/subscriptions/" + id, otherKey)).statusCode()).isEqualTo(404);
        assertThat(json(send(get("/v1/subscriptions/" + id))).get("status").stringValue()).isEqualTo("ACTIVE");
    }

    @Test
    void requiresTheSubscriptionScope() {
        ClientId client = createClient("initech");
        String eventsKey = issueKey(client, Scope.EVENTS_WRITE, Scope.EVENTS_READ);

        assertThat(send(get("/v1/subscriptions", eventsKey)).statusCode()).isEqualTo(403);
        assertThat(send(post("/v1/subscriptions", body(PUBLIC_URL, "order.placed"), eventsKey)).statusCode())
                .isEqualTo(403);
        assertThat(send(request("/v1/subscriptions").GET()).statusCode()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://" + PRIVATE_HOST + "/webhooks",
            "https://" + REBINDING_HOST + "/webhooks",
            "https://127.0.0.1/webhooks",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/webhooks",
            "https://[::ffff:10.0.0.1]/webhooks",
            "https://localhost/webhooks",
            "https://unknown.acme.example/webhooks",
            "https://" + PUBLIC_HOST + ":8443/webhooks"})
    void rejectsEndpointsThatAreNotPublicDestinations(String url) {
        HttpResponse<String> response = send(post("/v1/subscriptions", body(url, "order.placed")));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(json(response).get("type").stringValue()).isEqualTo("urn:eventcore:problem:endpoint-not-allowed");
        assertThat(json(response).get("violations").get(0).stringValue()).startsWith("url: ")
                .doesNotContain("10.0.0.5", "127.0.0.1");
        assertThat(storedSubscriptionCount()).isZero();
    }

    @Test
    void validatesTheSubscriptionRequest() {
        HttpResponse<String> plainHttp = send(post("/v1/subscriptions", body("http://" + PUBLIC_HOST, "order.placed")));
        HttpResponse<String> credentials = send(post("/v1/subscriptions",
                body("https://user:pass@" + PUBLIC_HOST + "/webhooks", "order.placed")));
        HttpResponse<String> noTypes = send(post("/v1/subscriptions", "{\"url\": \"" + PUBLIC_URL + "\", \"eventTypes\": []}"));
        HttpResponse<String> unknownField = send(post("/v1/subscriptions",
                "{\"url\": \"" + PUBLIC_URL + "\", \"eventTypes\": [\"order.placed\"], \"secret\": \"whsec_x\"}"));
        HttpResponse<String> unknownTypes = send(post("/v1/subscriptions",
                body(PUBLIC_URL, "order.placed", "order.shipped", "invoice.paid")));

        assertThat(plainHttp.statusCode()).isEqualTo(400);
        assertThat(json(plainHttp).get("violations").get(0).stringValue())
                .isEqualTo("url: must be an absolute https URL");
        assertThat(credentials.statusCode()).isEqualTo(400);
        assertThat(noTypes.statusCode()).isEqualTo(400);
        assertThat(unknownField.statusCode()).isEqualTo(400);
        assertThat(unknownTypes.statusCode()).isEqualTo(422);
        assertThat(json(unknownTypes).get("type").stringValue()).isEqualTo("urn:eventcore:problem:unknown-event-types");
        assertThat(json(unknownTypes).get("violations").values()).extracting(JsonNode::stringValue).containsExactly(
                "eventTypes: 'invoice.paid' is not registered in the event catalog",
                "eventTypes: 'order.shipped' is not registered in the event catalog");
        assertThat(storedSubscriptionCount()).isZero();
    }

    @Test
    void limitsTheNumberOfSubscriptionsPerClient() {
        for (int index = 0; index < 3; index++) {
            assertThat(send(post("/v1/subscriptions", body(PUBLIC_URL, "order.placed"))).statusCode()).isEqualTo(201);
        }

        HttpResponse<String> overLimit = send(post("/v1/subscriptions", body(PUBLIC_URL, "order.placed")));
        String first = json(send(get("/v1/subscriptions"))).get(0).get("id").stringValue();
        int afterDeletion = send(delete("/v1/subscriptions/" + first)).statusCode();

        assertThat(overLimit.statusCode()).isEqualTo(409);
        assertThat(json(overLimit).get("type").stringValue())
                .isEqualTo("urn:eventcore:problem:subscription-limit-reached");
        assertThat(afterDeletion).isEqualTo(204);
        assertThat(send(post("/v1/subscriptions", body(PUBLIC_URL, "order.placed"))).statusCode()).isEqualTo(201);
    }

    @Test
    void changesOnlyTheProvidedAttributes() {
        String id = json(send(post("/v1/subscriptions", body(PUBLIC_URL, "order.placed")))).get("id").stringValue();

        HttpResponse<String> paused = send(patch("/v1/subscriptions/" + id, "{\"status\": \"PAUSED\"}"));
        HttpResponse<String> moved = send(patch("/v1/subscriptions/" + id,
                "{\"url\": \"https://" + OTHER_PUBLIC_HOST + "/in\", \"eventTypes\": [\"payment.captured\"]}"));
        HttpResponse<String> privateTarget = send(patch("/v1/subscriptions/" + id,
                "{\"url\": \"https://" + PRIVATE_HOST + "/in\"}"));
        HttpResponse<String> empty = send(patch("/v1/subscriptions/" + id, "{}"));
        HttpResponse<String> unknownStatus = send(patch("/v1/subscriptions/" + id, "{\"status\": \"DELETED\"}"));

        assertThat(paused.statusCode()).isEqualTo(200);
        assertThat(json(paused).get("status").stringValue()).isEqualTo("PAUSED");
        assertThat(json(paused).get("url").stringValue()).isEqualTo(PUBLIC_URL);
        assertThat(moved.statusCode()).isEqualTo(200);
        assertThat(json(moved).get("status").stringValue()).isEqualTo("PAUSED");
        assertThat(json(moved).get("url").stringValue()).isEqualTo("https://" + OTHER_PUBLIC_HOST + "/in");
        assertThat(json(moved).get("eventTypes").get(0).stringValue()).isEqualTo("payment.captured");
        assertThat(Instant.parse(json(moved).get("updatedAt").stringValue()))
                .isAfterOrEqualTo(Instant.parse(json(moved).get("createdAt").stringValue()));
        assertThat(privateTarget.statusCode()).isEqualTo(422);
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat(unknownStatus.statusCode()).isEqualTo(400);
        assertThat(json(send(get("/v1/subscriptions/" + id))).get("url").stringValue())
                .isEqualTo("https://" + OTHER_PUBLIC_HOST + "/in");
    }

    @Test
    void rotatesTheSecretKeepingThePreviousOneDuringTheGracePeriod() throws GeneralSecurityException {
        JsonNode subscription = json(send(post("/v1/subscriptions", body(PUBLIC_URL, "order.placed"))));
        String id = subscription.get("id").stringValue();
        String original = subscription.get("secret").stringValue();

        HttpResponse<String> rotated = send(post("/v1/subscriptions/" + id + "/rotate-secret", ""));

        assertThat(rotated.statusCode()).isEqualTo(200);
        String replacement = json(rotated).get("secret").stringValue();
        Instant previousExpiresAt = Instant.parse(json(rotated).get("previousSecretExpiresAt").stringValue());
        assertThat(replacement).matches("whsec_[A-Za-z0-9+/]{43}=").isNotEqualTo(original);
        assertThat(previousExpiresAt).isBetween(Instant.now().plus(Duration.ofHours(23)),
                Instant.now().plus(Duration.ofHours(25)));

        List<StoredSecret> stored = storedSecrets(id);
        assertThat(stored).hasSize(2);
        Map<Boolean, byte[]> byCurrent = Map.of(
                stored.get(0).expiresAt() == null, decrypt(stored.get(0)),
                stored.get(1).expiresAt() == null, decrypt(stored.get(1)));
        assertThat(byCurrent.get(true)).isEqualTo(decodedKey(replacement));
        assertThat(byCurrent.get(false)).isEqualTo(decodedKey(original));
        assertThat(stored).filteredOn(secret -> secret.expiresAt() != null).singleElement()
                .satisfies(secret -> assertThat(secret.expiresAt().toInstant()).isEqualTo(previousExpiresAt));
    }

    @Test
    void discardsSecretsWhoseGracePeriodEnded() {
        String id = json(send(post("/v1/subscriptions", body(PUBLIC_URL, "order.placed")))).get("id").stringValue();
        send(post("/v1/subscriptions/" + id + "/rotate-secret", ""));
        jdbc.sql("update subscription_secret set expires_at = created_at where expires_at is not null").update();

        send(post("/v1/subscriptions/" + id + "/rotate-secret", ""));

        assertThat(storedSecrets(id)).hasSize(2).filteredOn(secret -> secret.expiresAt() == null).hasSize(1);
    }

    @Test
    void deletingASubscriptionDestroysItsSecrets() {
        String id = json(send(post("/v1/subscriptions", body(PUBLIC_URL, "order.placed")))).get("id").stringValue();
        send(post("/v1/subscriptions/" + id + "/rotate-secret", ""));

        assertThat(send(delete("/v1/subscriptions/" + id)).statusCode()).isEqualTo(204);
        assertThat(send(delete("/v1/subscriptions/" + id)).statusCode()).isEqualTo(404);
        assertThat(send(get("/v1/subscriptions/" + id)).statusCode()).isEqualTo(404);
        assertThat(send(post("/v1/subscriptions/" + id + "/rotate-secret", "")).statusCode()).isEqualTo(404);
        assertThat(send(patch("/v1/subscriptions/" + id, "{\"status\": \"ACTIVE\"}")).statusCode()).isEqualTo(404);
        assertThat(json(send(get("/v1/subscriptions"))).size()).isZero();
        assertThat(storedSecrets(id)).isEmpty();
        assertThat(jdbc.sql("select status from subscription where id = :id").param("id", UUID.fromString(id))
                .query(String.class).single()).isEqualTo("DELETED");
    }

    private record StoredSecret(UUID id, String keyId, byte[] nonce, byte[] ciphertext, OffsetDateTime expiresAt) {
    }

    private List<StoredSecret> storedSecrets(String subscriptionId) {
        return jdbc.sql("""
                        select id, key_id, nonce, ciphertext, expires_at from subscription_secret
                         where subscription_id = :id order by created_at, id
                        """)
                .param("id", UUID.fromString(subscriptionId))
                .query((row, rowNumber) -> new StoredSecret(row.getObject("id", UUID.class), row.getString("key_id"),
                        row.getBytes("nonce"), row.getBytes("ciphertext"),
                        row.getObject("expires_at", OffsetDateTime.class)))
                .list();
    }

    private long storedSubscriptionCount() {
        return jdbc.sql("select count(*) from subscription").query(Long.class).single();
    }

    private static byte[] decrypt(StoredSecret stored) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(TEST_ENCRYPTION_KEY, "AES"),
                new GCMParameterSpec(128, stored.nonce()));
        cipher.updateAAD(("eventcore-webhook-secret\n" + stored.keyId() + "\n" + stored.id())
                .getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(stored.ciphertext());
    }

    private static byte[] decodedKey(String secret) {
        return Base64.getDecoder().decode(secret.substring("whsec_".length()));
    }

    private static String body(String url, String... eventTypes) {
        return JSON.writeValueAsString(Map.of("url", url, "eventTypes", List.of(eventTypes)));
    }

    private HttpRequest.Builder post(String path, String body) {
        return post(path, body, apiKey);
    }

    private HttpRequest.Builder post(String path, String body, String key) {
        return request(path).header("Authorization", bearer(key)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder patch(String path, String body) {
        return patch(path, body, apiKey);
    }

    private HttpRequest.Builder patch(String path, String body, String key) {
        return request(path).header("Authorization", bearer(key)).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder get(String path) {
        return get(path, apiKey);
    }

    private HttpRequest.Builder get(String path, String key) {
        return request(path).header("Authorization", bearer(key)).GET();
    }

    private HttpRequest.Builder delete(String path) {
        return delete(path, apiKey);
    }

    private HttpRequest.Builder delete(String path, String key) {
        return request(path).header("Authorization", bearer(key)).DELETE();
    }
}
