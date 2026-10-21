package com.eventcore.platform.roles;

import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

final class ConfiguredRoles {

    private ConfiguredRoles() {
    }

    static Set<String> of(Environment environment) {
        return Binder.get(environment)
                .bind("eventcore.roles", Bindable.setOf(String.class))
                .orElse(Set.of());
    }

    static boolean includes(Set<String> configured, Role role) {
        return configured.stream().anyMatch(candidate -> role.name().equalsIgnoreCase(candidate.strip()));
    }
}
