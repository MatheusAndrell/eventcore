package com.eventcore.subscription.application;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("eventcore.security")
public record WebhookSecretProperties(List<String> webhookSecretKeys, String activeWebhookSecretKey) {

    public WebhookSecretProperties {
        webhookSecretKeys = webhookSecretKeys == null ? List.of() : List.copyOf(webhookSecretKeys);
    }

    @Override
    public String toString() {
        return "WebhookSecretProperties[webhookSecretKeys=<redacted>, activeWebhookSecretKey="
                + activeWebhookSecretKey + "]";
    }
}
