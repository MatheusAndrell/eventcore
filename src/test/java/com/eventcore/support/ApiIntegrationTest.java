package com.eventcore.support;

import com.eventcore.access.application.ClientAdministration;
import com.eventcore.access.domain.ClientName;
import com.eventcore.access.domain.Scope;
import com.eventcore.platform.identity.ClientId;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eventcore.roles=api",
                "management.server.port=0",
                "eventcore.ingestion.idempotency-lock-timeout=500ms"})
@Import(TestcontainersConfiguration.class)
public abstract class ApiIntegrationTest {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected ClientAdministration administration;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("truncate outbox_message, idempotency_record, event, api_key, client cascade").update();
    }

    protected ClientId createClient(String name) {
        return administration.createClient(new ClientName(name)).id();
    }

    protected String issueKey(ClientId client, Scope... scopes) {
        return administration.issueApiKey(client, Set.of(scopes), Optional.empty())
                .orElseThrow()
                .token()
                .value();
    }

    protected HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
    }

    protected HttpRequest.Builder managementRequest(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path))
                .timeout(Duration.ofSeconds(15));
    }

    protected HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("HTTP request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted", exception);
        }
    }

    protected static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    protected static String bearer(String apiKey) {
        return "Bearer " + apiKey;
    }
}
