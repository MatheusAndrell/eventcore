package com.eventcore.platform.roles;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("eventcore")
public record RoleProperties(@NotEmpty Set<@NotNull Role> roles) {

    public RoleProperties {
        roles = roles == null ? null : Set.copyOf(roles);
    }
}
