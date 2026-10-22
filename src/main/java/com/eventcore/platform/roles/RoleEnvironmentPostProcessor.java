package com.eventcore.platform.roles;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class RoleEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SERVER_PORT_SOURCE = "eventcoreRoleServerPort";
    static final String DEFAULTS_SOURCE = "eventcoreRoleDefaults";
    static final int DISABLED_PORT = -1;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Set<String> roles = ConfiguredRoles.of(environment);
        if (roles.isEmpty()) {
            return;
        }
        if (!ConfiguredRoles.includes(roles, Role.API)) {
            environment.getPropertySources()
                    .addFirst(new MapPropertySource(SERVER_PORT_SOURCE, Map.of("server.port", DISABLED_PORT)));
        }
        boolean usesBroker = ConfiguredRoles.includes(roles, Role.RELAY) || ConfiguredRoles.includes(roles, Role.WORKER);
        environment.getPropertySources().addLast(new MapPropertySource(DEFAULTS_SOURCE, Map.of(
                "management.health.rabbit.enabled", usesBroker,
                "management.endpoint.health.group.readiness.include",
                usesBroker ? "readinessState,db,rabbit" : "readinessState,db")));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
