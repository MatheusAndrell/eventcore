package com.eventcore.access.web;

import com.eventcore.access.application.IssuedApiKey;
import com.eventcore.access.domain.ApiKey;
import com.eventcore.access.domain.Client;
import com.eventcore.access.domain.ClientStatus;
import com.eventcore.access.domain.Scope;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

final class AdminRepresentations {

    private AdminRepresentations() {
    }

    record CreateClientRequest(@NotBlank String name) {
    }

    record ChangeClientStatusRequest(@NotNull ClientStatus status) {
    }

    record IssueApiKeyRequest(@NotEmpty List<@NotBlank String> scopes, Instant expiresAt) {
    }

    record ClientResponse(String id, String name, String status, Instant createdAt) {

        static ClientResponse of(Client client) {
            return new ClientResponse(client.id().toString(), client.name().value(), client.status().name(),
                    client.createdAt());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApiKeyResponse(
            String id,
            String keyId,
            List<String> scopes,
            String status,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            Instant lastUsedAt) {

        static ApiKeyResponse of(ApiKey apiKey) {
            return new ApiKeyResponse(apiKey.id().toString(), apiKey.keyId(), scopeValues(apiKey),
                    apiKey.status().name(), apiKey.createdAt(), apiKey.expiresAt().orElse(null),
                    apiKey.revokedAt().orElse(null), apiKey.lastUsedAt().orElse(null));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record IssuedApiKeyResponse(
            String id, String keyId, String apiKey, List<String> scopes, Instant createdAt, Instant expiresAt) {

        static IssuedApiKeyResponse of(IssuedApiKey issued) {
            ApiKey apiKey = issued.apiKey();
            return new IssuedApiKeyResponse(apiKey.id().toString(), apiKey.keyId(), issued.token().value(),
                    scopeValues(apiKey), apiKey.createdAt(), apiKey.expiresAt().orElse(null));
        }
    }

    private static List<String> scopeValues(ApiKey apiKey) {
        return apiKey.scopes().stream().map(Scope::value).sorted().toList();
    }
}
