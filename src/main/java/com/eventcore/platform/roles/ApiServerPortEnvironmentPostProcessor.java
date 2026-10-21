package com.eventcore.platform.roles;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class ApiServerPortEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "eventcoreApiServerPort";
    static final int DISABLED_PORT = -1;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Set<String> roles = Binder.get(environment)
                .bind("eventcore.roles", Bindable.setOf(String.class))
                .orElse(Set.of());
        if (!roles.isEmpty() && roles.stream().noneMatch(ApiServerPortEnvironmentPostProcessor::isApi)) {
            environment.getPropertySources()
                    .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of("server.port", DISABLED_PORT)));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static boolean isApi(String role) {
        return Role.API.name().equalsIgnoreCase(role.strip());
    }
}
