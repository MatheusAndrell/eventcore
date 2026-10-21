package com.eventcore.platform.roles;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ApiServerPortEnvironmentPostProcessorTest {

    private final ApiServerPortEnvironmentPostProcessor postProcessor = new ApiServerPortEnvironmentPostProcessor();

    @ParameterizedTest
    @ValueSource(strings = {"relay", "worker", "relay,worker", " WORKER "})
    void disablesTheApiPortWhenTheApiRoleIsAbsent(String roles) {
        StandardEnvironment environment = environmentWith(Map.of("eventcore.roles", roles, "server.port", "8080"));

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(-1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "API", "api,worker", "relay, api ,worker"})
    void keepsTheConfiguredPortWhenTheApiRoleIsPresent(String roles) {
        StandardEnvironment environment = environmentWith(Map.of("eventcore.roles", roles, "server.port", "8080"));

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8080);
        assertThat(environment.getPropertySources().contains(ApiServerPortEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
    }

    @Test
    void leavesTheEnvironmentUntouchedWhenRolesAreMissing() {
        StandardEnvironment environment = environmentWith(Map.of("server.port", "8080"));

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8080);
    }

    private static StandardEnvironment environmentWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }
}
