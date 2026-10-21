package com.eventcore.access.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventcore.access.domain.Scope;
import com.eventcore.support.ApiIntegrationTest;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdminApiIT extends ApiIntegrationTest {

    private String admin;

    @BeforeEach
    void createAdministrator() {
        admin = issueKey(createClient("ops"), Scope.ADMIN);
    }

    @Test
    void createsClientsWithUniqueNames() {
        HttpResponse<String> created = send(post("/admin/clients", "{\"name\": \"acme\"}"));
        HttpResponse<String> duplicate = send(post("/admin/clients", "{\"name\": \"acme\"}"));
        HttpResponse<String> invalidName = send(post("/admin/clients", "{\"name\": \"Acme Corp\"}"));
        HttpResponse<String> unknownField = send(post("/admin/clients", "{\"name\": \"x\", \"plan\": \"gold\"}"));

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode client = json(created);
        assertThat(client.get("name").stringValue()).isEqualTo("acme");
        assertThat(client.get("status").stringValue()).isEqualTo("ACTIVE");
        assertThat(created.headers().firstValue("Location")).contains("/admin/clients/" + client.get("id").stringValue());
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json(duplicate).get("type").stringValue()).isEqualTo("urn:eventcore:problem:client-name-taken");
        assertThat(invalidName.statusCode()).isEqualTo(400);
        assertThat(unknownField.statusCode()).isEqualTo(400);
    }

    @Test
    void issuesListsAndRevokesApiKeys() {
        String clientId = json(send(post("/admin/clients", "{\"name\": \"acme\"}"))).get("id").stringValue();

        HttpResponse<String> issued = send(post("/admin/clients/" + clientId + "/api-keys",
                "{\"scopes\": [\"events:read\", \"events:write\"], \"expiresAt\": \"2999-01-01T00:00:00Z\"}"));

        assertThat(issued.statusCode()).isEqualTo(201);
        JsonNode key = json(issued);
        String apiKey = key.get("apiKey").stringValue();
        assertThat(apiKey).matches("ec_[a-z2-7]{16}_[A-Za-z0-9_-]{43}");
        assertThat(key.get("scopes").values()).extracting(JsonNode::stringValue)
                .containsExactly("events:read", "events:write");
        assertThat(send(eventsWith(apiKey)).statusCode()).isEqualTo(200);

        HttpResponse<String> listed = send(get("/admin/clients/" + clientId + "/api-keys"));
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(json(listed).get(0).has("apiKey")).isFalse();
        assertThat(json(listed).get(0).get("keyId").stringValue()).isEqualTo(key.get("keyId").stringValue());
        assertThat(listed.body()).doesNotContain(apiKey.substring(apiKey.length() - 43));

        String keyPath = "/admin/clients/" + clientId + "/api-keys/" + key.get("id").stringValue();
        assertThat(send(delete(keyPath)).statusCode()).isEqualTo(204);
        assertThat(send(eventsWith(apiKey)).statusCode()).isEqualTo(401);
        assertThat(send(delete(keyPath)).statusCode()).isEqualTo(404);
        assertThat(json(send(get("/admin/clients/" + clientId + "/api-keys"))).get(0).get("status").stringValue())
                .isEqualTo("REVOKED");
    }

    @Test
    void validatesKeyRequests() {
        String clientId = json(send(post("/admin/clients", "{\"name\": \"acme\"}"))).get("id").stringValue();

        HttpResponse<String> unknownScope = send(post("/admin/clients/" + clientId + "/api-keys",
                "{\"scopes\": [\"events:read\", \"everything\"]}"));
        HttpResponse<String> noScopes = send(post("/admin/clients/" + clientId + "/api-keys", "{\"scopes\": []}"));
        HttpResponse<String> pastExpiry = send(post("/admin/clients/" + clientId + "/api-keys",
                "{\"scopes\": [\"events:read\"], \"expiresAt\": \"2020-01-01T00:00:00Z\"}"));
        HttpResponse<String> unknownClient = send(post(
                "/admin/clients/0192f3c8-0000-7000-8000-0000000000ff/api-keys", "{\"scopes\": [\"events:read\"]}"));

        assertThat(unknownScope.statusCode()).isEqualTo(400);
        assertThat(json(unknownScope).get("violations").get(0).stringValue())
                .isEqualTo("scopes: unknown scope 'everything'");
        assertThat(noScopes.statusCode()).isEqualTo(400);
        assertThat(pastExpiry.statusCode()).isEqualTo(400);
        assertThat(unknownClient.statusCode()).isEqualTo(404);
    }

    @Test
    void disablingAClientBlocksItsKeys() {
        String clientId = json(send(post("/admin/clients", "{\"name\": \"acme\"}"))).get("id").stringValue();
        String apiKey = json(send(post("/admin/clients/" + clientId + "/api-keys",
                "{\"scopes\": [\"events:read\"]}"))).get("apiKey").stringValue();

        HttpResponse<String> disabled = send(put("/admin/clients/" + clientId + "/status", "{\"status\": \"DISABLED\"}"));
        int whileDisabled = send(eventsWith(apiKey)).statusCode();
        send(put("/admin/clients/" + clientId + "/status", "{\"status\": \"ACTIVE\"}"));

        assertThat(json(disabled).get("status").stringValue()).isEqualTo("DISABLED");
        assertThat(whileDisabled).isEqualTo(401);
        assertThat(send(eventsWith(apiKey)).statusCode()).isEqualTo(200);
        assertThat(send(put("/admin/clients/" + clientId + "/status", "{\"status\": \"GONE\"}")).statusCode())
                .isEqualTo(400);
    }

    private HttpRequest.Builder post(String path, String body) {
        return request(path).header("Authorization", bearer(admin)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder put(String path, String body) {
        return request(path).header("Authorization", bearer(admin)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder get(String path) {
        return request(path).header("Authorization", bearer(admin)).GET();
    }

    private HttpRequest.Builder delete(String path) {
        return request(path).header("Authorization", bearer(admin)).DELETE();
    }

    private HttpRequest.Builder eventsWith(String apiKey) {
        return request("/v1/events").header("Authorization", bearer(apiKey)).GET();
    }
}
