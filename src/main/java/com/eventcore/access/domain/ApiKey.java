package com.eventcore.access.domain;

import com.eventcore.platform.identity.ClientId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record ApiKey(
        UUID id,
        ClientId clientId,
        String keyId,
        Set<Scope> scopes,
        ApiKeyStatus status,
        Instant createdAt,
        Optional<Instant> expiresAt,
        Optional<Instant> revokedAt,
        Optional<Instant> lastUsedAt) {

    public ApiKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(revokedAt, "revokedAt");
        Objects.requireNonNull(lastUsedAt, "lastUsedAt");
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("an API key needs at least one scope");
        }
        scopes = Set.copyOf(scopes);
        if ((status == ApiKeyStatus.REVOKED) != revokedAt.isPresent()) {
            throw new IllegalArgumentException("revokedAt must be present exactly when the key is revoked");
        }
    }

    public boolean isUsableAt(Instant now) {
        return status == ApiKeyStatus.ACTIVE && expiresAt.map(now::isBefore).orElse(true);
    }
}
