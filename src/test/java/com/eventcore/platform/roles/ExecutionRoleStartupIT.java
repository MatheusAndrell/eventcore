package com.eventcore.platform.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventcore.EventcoreApplication;
import com.eventcore.platform.messaging.MessagingTopology;
import com.eventcore.subscription.application.WebhookSecretCipher;
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
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class ExecutionRoleStartupIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String UNREACHABLE_BROKER_PORT = "1";
    private static final String[] WITHOUT_WEBHOOK_SECRET_KEYS = {
            "--eventcore.security.webhook-secret-keys=", "--eventcore.security.active-webhook-secret-key="};

    @Container
    static final PostgreSQLContainer postgres = Containers.postgres();

    @Container
    static final RabbitMQContainer rabbitmq = Containers.rabbitmq();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void apiRoleServesTheApiPortAndManagementProbes() throws Exception {
        try (ConfigurableApplicationContext context = start(postgres, "api")) {
            Integer serverPort = context.getEnvironment().getProperty("local.server.port", Integer.class);

            assertThat(serverPort).isNotNull().isPositive();
            assertThat(context.containsBean("eventController")).isTrue();
            assertThat(context.containsBean("adminController")).isTrue();
            assertThat(get(serverPort, "/").statusCode()).isEqualTo(401);
            assertThat(get(managementPort(context), "/actuator/health/liveness").statusCode()).isEqualTo(200);
            assertThat(get(managementPort(context), "/actuator/health/readiness").statusCode()).isEqualTo(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"relay", "worker", "relay,worker"})
    void rolesWithoutApiExposeOnlyTheManagementPort(String roles) throws Exception {
        try (ConfigurableApplicationContext context = start(postgres, roles)) {
            assertThat(((WebServerApplicationContext) context).getWebServer().getPort()).isEqualTo(-1);
            assertThat(context.containsBean("eventController")).isFalse();
            assertThat(context.containsBean("adminController")).isFalse();
            assertThat(get(managementPort(context), "/actuator/health/liveness").statusCode()).isEqualTo(200);
            assertThat(get(managementPort(context), "/actuator/health/readiness").statusCode()).isEqualTo(200);
            assertThat(context.containsBean("eventcoreTopology")).isTrue();
            assertThat(context.getBean(AmqpAdmin.class).getQueueInfo(MessagingTopology.EVENT_ACCEPTED_QUEUE))
                    .isNotNull();
        }
    }

    @Test
    void apiRoleStaysReadyAndHealthyWithoutTheBroker() throws Exception {
        try (ConfigurableApplicationContext context = start(postgres, "api", UNREACHABLE_BROKER_PORT)) {
            assertThat(context.containsBean("eventcoreTopology")).isFalse();
            assertThat(get(managementPort(context), "/actuator/health/readiness").statusCode()).isEqualTo(200);
            assertThat(get(managementPort(context), "/actuator/health").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void relayRoleIsNotReadyWithoutTheBroker() throws Exception {
        try (ConfigurableApplicationContext context = start(postgres, "relay", UNREACHABLE_BROKER_PORT)) {
            assertThat(get(managementPort(context), "/actuator/health/readiness").statusCode()).isEqualTo(503);
            assertThat(get(managementPort(context), "/actuator/health/liveness").statusCode()).isEqualTo(200);
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
    void onlyRolesThatHandleWebhookSecretsRequireTheEncryptionKeys() {
        try (ConfigurableApplicationContext context = start(postgres, "relay", String.valueOf(rabbitmq.getAmqpPort()),
                WITHOUT_WEBHOOK_SECRET_KEYS)) {
            assertThat(context.getBeanNamesForType(WebhookSecretCipher.class)).isEmpty();
        }
        assertThatThrownBy(() -> start(postgres, "api", String.valueOf(rabbitmq.getAmqpPort()),
                WITHOUT_WEBHOOK_SECRET_KEYS).close())
                .rootCause()
                .hasMessage("eventcore.security.webhook-secret-keys must define at least one key");
    }

    @Test
    void applicationDoesNotStartWithoutRoles() {
        assertThatThrownBy(() -> start(postgres, null).close())
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("roles");
    }

    private static ConfigurableApplicationContext start(PostgreSQLContainer database, String roles) {
        return start(database, roles, String.valueOf(rabbitmq.getAmqpPort()));
    }

    private static ConfigurableApplicationContext start(
            PostgreSQLContainer database, String roles, String brokerPort, String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--spring.datasource.hikari.connection-timeout=2000",
                "--spring.rabbitmq.host=" + rabbitmq.getHost(),
                "--spring.rabbitmq.port=" + brokerPort,
                "--spring.rabbitmq.username=" + rabbitmq.getAdminUsername(),
                "--spring.rabbitmq.password=" + rabbitmq.getAdminPassword(),
                "--spring.rabbitmq.connection-timeout=1s"));
        args.addAll(List.of(extraArgs));
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
