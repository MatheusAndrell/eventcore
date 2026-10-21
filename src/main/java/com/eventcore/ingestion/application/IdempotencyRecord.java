package com.eventcore.ingestion.application;

import com.eventcore.ingestion.domain.EventId;
import com.eventcore.ingestion.domain.IdempotencyKey;
import com.eventcore.ingestion.domain.RequestFingerprint;
import com.eventcore.platform.identity.ClientId;
import java.time.Instant;
import java.util.Objects;

public record IdempotencyRecord(
        ClientId clientId,
        IdempotencyKey key,
        RequestFingerprint fingerprint,
        EventId eventId,
        Instant createdAt,
        Instant expiresAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }
}
