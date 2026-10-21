package com.eventcore.access.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventcore.access.domain.ApiKeyToken;
import com.eventcore.access.domain.ClientStatus;
import com.eventcore.access.domain.Scope;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.support.ApiIntegrationTest;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

@ExtendWith(OutputCaptureExtension.class)
class SecurityIT extends ApiIntegrationTest {

    private ClientId client;
    private String reader;

    @BeforeEach
    void createClient() {
        client = createClient("acme");
        reader = issueKey(client, Scope.EVENTS_READ);
    }

    @Test
    void rejectsEveryInvalidCredentialWithTheSameProblem() {
        String revoked = issueKey(client, Scope.EVENTS_READ);
        jdbc.sql("update api_key set status = 'REVOKED', revoked_at = now() where key_id = :keyId")
                .param("keyId", keyIdOf(revoked)).update();
        String expired = issueKey(client, Scope.EVENTS_READ);
        jdbc.sql("update api_key set created_at = now() - interval '2 days', expires_at = now() - interval '1 day'"
                        + " where key_id = :keyId")
                .param("keyId", keyIdOf(expired)).update();
        ClientId disabledClient = createClient("disabled");
        String disabled = issueKey(disabledClient, Scope.EVENTS_READ);
        administration.changeStatus(disabledClient, ClientStatus.DISABLED);
        String wrongSecret = reader.substring(0, reader.length() - 4) + (reader.endsWith("AAAA") ? "BBBB" : "AAAA");
        String unknown = ApiKeyToken.generate(new SecureRandom()).value();

        List<HttpResponse<String>> responses = Stream.of(
                        request("/v1/events"),
                        request("/v1/events").header("Authorization", "Basic dXNlcjpwYXNz"),
                        request("/v1/events").header("Authorization", bearer("not-a-key")),
                        request("/v1/events").header("Authorization", bearer(unknown)),
                        request("/v1/events").header("Authorization", bearer(wrongSecret)),
                        request("/v1/events").header("Authorization", bearer(revoked)),
                        request("/v1/events").header("Authorization", bearer(expired)),
                        request("/v1/events").header("Authorization", bearer(disabled)))
                .map(this::send)
                .toList();

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
            JsonNode problem = json(response);
            assertThat(problem.get("type").stringValue()).isEqualTo("urn:eventcore:problem:unauthorized");
            assertThat(problem.get("detail").stringValue()).isEqualTo("a valid API key is required");
        });
        assertThat(send(request("/v1/events").header("Authorization", bearer(reader))).statusCode()).isEqualTo(200);
    }

    @Test
    void enforcesScopesPerOperation() {
        HttpResponse<String> write = send(request("/v1/events")
                .header("Authorization", bearer(reader))
                .header("Idempotency-Key", "key-1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        HttpResponse<String> admin = send(request("/admin/clients/" + client).header("Authorization", bearer(reader)));
        HttpResponse<String> unknownPath = send(request("/internal").header("Authorization", bearer(reader)));

        assertThat(write.statusCode()).isEqualTo(403);
        assertThat(json(write).get("type").stringValue()).isEqualTo("urn:eventcore:problem:insufficient-scope");
        assertThat(admin.statusCode()).isEqualTo(403);
        assertThat(unknownPath.statusCode()).isEqualTo(403);
        assertThat(send(request("/internal")).statusCode()).isEqualTo(401);
    }

    @Test
    void exposesOnlyAnonymousProbesOnTheManagementPort() {
        assertThat(send(managementRequest("/actuator/health/liveness")).statusCode()).isEqualTo(200);
        assertThat(send(managementRequest("/actuator/health/readiness")).statusCode()).isEqualTo(200);
        assertThat(send(managementRequest("/actuator/env")).statusCode()).isIn(401, 403, 404);
        assertThat(send(managementRequest("/actuator/env").header("Authorization", bearer(reader))).statusCode())
                .isIn(403, 404);
    }

    @Test
    void servesTheOpenApiDocumentOnlyToAuthenticatedClients() {
        HttpResponse<String> anonymous = send(request("/v3/api-docs"));
        HttpResponse<String> authenticated = send(request("/v3/api-docs").header("Authorization", bearer(reader)));

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(authenticated.statusCode()).isEqualTo(200);
        JsonNode paths = json(authenticated).get("paths");
        assertThat(paths.has("/v1/events")).isTrue();
        assertThat(paths.has("/v1/events/{eventId}")).isTrue();
        assertThat(paths.has("/admin/clients")).isTrue();
    }

    @Test
    void recordsWhenAKeyIsUsed() {
        send(request("/v1/events").header("Authorization", bearer(reader)));

        assertThat(jdbc.sql("select last_used_at is not null from api_key where key_id = :keyId")
                .param("keyId", keyIdOf(reader)).query(Boolean.class).single()).isTrue();
    }

    @Test
    void neverWritesSecretsToTheLog(CapturedOutput output) {
        String wrongSecret = reader.substring(0, reader.length() - 4) + (reader.endsWith("AAAA") ? "BBBB" : "AAAA");
        ClientId other = createClient("globex");
        HttpResponse<String> issued = send(request("/admin/clients/" + other + "/api-keys")
                .header("Authorization", bearer(issueKey(createClient("ops"), Scope.ADMIN)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"scopes\": [\"events:read\"]}")));
        send(request("/v1/events").header("Authorization", bearer(wrongSecret)));
        send(request("/v1/events").header("Authorization", bearer(reader)));

        String issuedKey = json(issued).get("apiKey").stringValue();
        assertThat(output.getAll())
                .doesNotContain(secretOf(reader))
                .doesNotContain(secretOf(wrongSecret))
                .doesNotContain(secretOf(issuedKey));
    }

    private static String keyIdOf(String apiKey) {
        return ApiKeyToken.parse(apiKey).orElseThrow().keyId();
    }

    private static String secretOf(String apiKey) {
        return apiKey.substring(apiKey.length() - 43);
    }
}
