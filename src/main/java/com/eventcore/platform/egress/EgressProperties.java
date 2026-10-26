package com.eventcore.platform.egress;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("eventcore.egress")
public record EgressProperties(@DefaultValue("443") @NotEmpty Set<@NotNull @Min(1) @Max(65535) Integer> allowedPorts) {

    public EgressProperties {
        allowedPorts = allowedPorts == null ? Set.of() : Set.copyOf(allowedPorts);
    }
}
