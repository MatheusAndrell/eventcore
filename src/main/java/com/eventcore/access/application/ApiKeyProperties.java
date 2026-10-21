package com.eventcore.access.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("eventcore.security")
public record ApiKeyProperties(
        @NotEmpty List<String> apiKeyPeppers,
        @NotBlank String activeApiKeyPepper,
        String bootstrapAdminKey) {

    public ApiKeyProperties {
        apiKeyPeppers = apiKeyPeppers == null ? List.of() : List.copyOf(apiKeyPeppers);
    }

    public Optional<String> configuredBootstrapAdminKey() {
        return bootstrapAdminKey == null || bootstrapAdminKey.isBlank()
                ? Optional.empty()
                : Optional.of(bootstrapAdminKey.strip());
    }

    @Override
    public String toString() {
        return "ApiKeyProperties[apiKeyPeppers=<redacted>, activeApiKeyPepper=" + activeApiKeyPepper
                + ", bootstrapAdminKey=<redacted>]";
    }
}
