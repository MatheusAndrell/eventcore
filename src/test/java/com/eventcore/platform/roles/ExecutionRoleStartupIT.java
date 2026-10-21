package com.eventcore.platform.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventcore.EventcoreApplication;
import com.eventcore.support.Containers;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class ExecutionRoleStartupIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer postgres = Containers.postgres();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void apiRoleServesTheApiPortAndManagementProbes() throws Exception {
        try (ConfigurableApplicationContext context = start(postgres, "api")) {
            Integer serverPort = context.getEnvironment().getProperty("local.server.port", Integer.class);

            assertThat(serverPort).isNotNull().isPositive();
            assertThat(get(serverPort, "/").statusCode()).isEqualTo(404);
            assertThat(get(managementPort(context), "/actuator/health/liveness").statusCode()).isEqualTo(200);
            assertThat(get(managementPort(context), "/actuator/health/readiness").statusCode()).isEqualTo(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"relay", "worker", "relay,worker"})
    void rolesWithoutApiExposeOnlyTheManagementPort(String roles) throws Exception {
        try (ConfigurableApplicationContext context = start(postgres, roles)) {
            assertThat(((WebServerApplicationContext) context).getWebServer().getPort()).isEqualTo(-1);
            assertThat(get(managementPort(context), "/actuator/health/liveness").statusCode()).isEqualTo(200);
            assertThat(get(managementPort(context), "/actuator/health/readiness").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void healthResponsesDoNotExposeDetails() throws Exception {
        try (ConfigurableApplicationContext context = start(postgres, "api")) {
            HttpResponse<String> health = get(managementPort(context), "/actuator/health");

            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(health.body()))
                    .isEqualTo(JSON.readTree("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}"));
        }
    }

    @Test
    void readinessFailsWhileTheDatabaseIsUnavailable() throws Exception {
        try (PostgreSQLContainer database = Containers.postgres()) {
            database.start();
            try (ConfigurableApplicationContext context = start(database, "worker")) {
                int managementPort = managementPort(context);
                assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);

                database.stop();

                assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(503);
                assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
            }
        }
    }

    @Test
    void applicationDoesNotStartWithoutRoles() {
        assertThatThrownBy(() -> start(postgres, null).close())
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("roles");
    }

    private static ConfigurableApplicationContext start(PostgreSQLContainer database, String roles) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--spring.datasource.hikari.connection-timeout=2000"));
        if (roles != null) {
            args.add("--eventcore.roles=" + roles);
        }
        return new SpringApplicationBuilder(EventcoreApplication.class).run(args.toArray(String[]::new));
    }

    private static int managementPort(ConfigurableApplicationContext context) {
        return context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
