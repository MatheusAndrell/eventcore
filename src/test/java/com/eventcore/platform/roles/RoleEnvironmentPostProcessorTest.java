package com.eventcore.platform.roles;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class RoleEnvironmentPostProcessorTest {

    private final RoleEnvironmentPostProcessor postProcessor = new RoleEnvironmentPostProcessor();

    @ParameterizedTest
    @ValueSource(strings = {"relay", "worker", "relay,worker", " WORKER "})
    void disablesTheApiPortWhenTheApiRoleIsAbsent(String roles) {
        StandardEnvironment environment = process(Map.of("eventcore.roles", roles, "server.port", "8080"));

        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(-1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "API", "api,worker", "relay, api ,worker"})
    void keepsTheConfiguredPortWhenTheApiRoleIsPresent(String roles) {
        StandardEnvironment environment = process(Map.of("eventcore.roles", roles, "server.port", "8080"));

        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8080);
        assertThat(environment.getPropertySources().contains(RoleEnvironmentPostProcessor.SERVER_PORT_SOURCE))
                .isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "api|false|readinessState,db",
            "relay|true|readinessState,db,rabbit",
            "worker|true|readinessState,db,rabbit",
            "api,relay,worker|true|readinessState,db,rabbit"
    })
    void makesReadinessDependOnTheBrokerOnlyForBrokerRoles(String roles, boolean rabbitHealth, String readiness) {
        StandardEnvironment environment = process(Map.of("eventcore.roles", roles));

        assertThat(environment.getProperty("management.health.rabbit.enabled", Boolean.class)).isEqualTo(rabbitHealth);
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo(readiness);
    }

    @Test
    void letsExplicitConfigurationOverrideTheRoleDefaults() {
        StandardEnvironment environment = process(Map.of(
                "eventcore.roles", "relay",
                "management.endpoint.health.group.readiness.include", "readinessState"));

        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState");
    }

    @Test
    void leavesTheEnvironmentUntouchedWhenRolesAreMissing() {
        StandardEnvironment environment = process(Map.of("server.port", "8080"));

        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8080);
        assertThat(environment.getPropertySources().contains(RoleEnvironmentPostProcessor.DEFAULTS_SOURCE)).isFalse();
    }

    private StandardEnvironment process(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        postProcessor.postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
