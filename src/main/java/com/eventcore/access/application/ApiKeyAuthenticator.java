package com.eventcore.access.application;

import com.eventcore.access.domain.ApiKey;
import com.eventcore.access.domain.ApiKeyToken;
import com.eventcore.access.domain.ClientStatus;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ApiKeyAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);
    private static final Duration USE_RECORDING_INTERVAL = Duration.ofMinutes(1);
    private static final byte[] UNKNOWN_KEY_HASH = new byte[32];

    private final AccessStore store;
    private final ApiKeyHasher hasher;
    private final Clock clock;
    private final ConcurrentMap<UUID, Instant> lastRecordedUse = new ConcurrentHashMap<>();

    public ApiKeyAuthenticator(AccessStore store, ApiKeyHasher hasher, Clock clock) {
        this.store = store;
        this.hasher = hasher;
        this.clock = clock;
    }

    public Optional<AuthenticatedClient> authenticate(String presented) {
        Optional<ApiKeyToken> token = ApiKeyToken.parse(presented);
        if (token.isEmpty()) {
            spendComparableWork(presented == null ? "" : presented);
            log.debug("API key rejected: malformed token");
            return Optional.empty();
        }
        return authenticate(token.get());
    }

    private Optional<AuthenticatedClient> authenticate(ApiKeyToken token) {
        Optional<StoredCredential> stored = store.findCredential(token.keyId());
        if (stored.isEmpty()) {
            spendComparableWork(token.secret());
            log.debug("API key rejected: unknown key id {}", token.keyId());
            return Optional.empty();
        }
        StoredCredential credential = stored.get();
        Optional<byte[]> presentedHash = hasher.hash(credential.pepperId(), token.secret());
        if (presentedHash.isEmpty()) {
            log.warn("API key rejected: key id {} uses pepper '{}', which is no longer configured",
                    token.keyId(), credential.pepperId());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(presentedHash.get(), credential.secretHash())) {
            log.debug("API key rejected: secret mismatch for key id {}", token.keyId());
            return Optional.empty();
        }
        return authorize(credential);
    }

    private Optional<AuthenticatedClient> authorize(StoredCredential credential) {
        ApiKey apiKey = credential.apiKey();
        Instant now = clock.instant();
        if (!apiKey.isUsableAt(now)) {
            log.debug("API key rejected: key id {} is revoked or expired", apiKey.keyId());
            return Optional.empty();
        }
        if (credential.clientStatus() != ClientStatus.ACTIVE) {
            log.debug("API key rejected: client {} of key id {} is disabled", apiKey.clientId(), apiKey.keyId());
            return Optional.empty();
        }
        recordUse(apiKey.id(), now);
        return Optional.of(new AuthenticatedClient(apiKey.clientId(), apiKey.keyId(), apiKey.scopes()));
    }

    private boolean spendComparableWork(String secret) {
        return MessageDigest.isEqual(hasher.hashWithActivePepper(secret), UNKNOWN_KEY_HASH);
    }

    private void recordUse(UUID apiKeyId, Instant now) {
        Instant previous = lastRecordedUse.get(apiKeyId);
        if (previous == null || !previous.plus(USE_RECORDING_INTERVAL).isAfter(now)) {
            lastRecordedUse.put(apiKeyId, now);
            store.recordApiKeyUse(apiKeyId, now);
        }
    }
}
