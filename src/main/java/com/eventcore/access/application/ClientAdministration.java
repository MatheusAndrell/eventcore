package com.eventcore.access.application;

import com.eventcore.access.domain.ApiKey;
import com.eventcore.access.domain.ApiKeyStatus;
import com.eventcore.access.domain.ApiKeyToken;
import com.eventcore.access.domain.Client;
import com.eventcore.access.domain.ClientName;
import com.eventcore.access.domain.ClientStatus;
import com.eventcore.access.domain.Scope;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.identity.UuidV7Generator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ClientAdministration {

    static final ClientName ADMIN_CLIENT_NAME = new ClientName("eventcore-admin");

    private final AccessStore store;
    private final ApiKeyHasher hasher;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ClientAdministration(AccessStore store, ApiKeyHasher hasher, UuidV7Generator ids, Clock clock) {
        this.store = store;
        this.hasher = hasher;
        this.ids = ids;
        this.clock = clock;
    }

    public Client createClient(ClientName name) {
        Client client = new Client(new ClientId(ids.next()), name, ClientStatus.ACTIVE, now());
        if (!store.insertClient(client)) {
            throw new ClientNameTakenException(name);
        }
        return client;
    }

    public Optional<Client> findClient(ClientId clientId) {
        return store.findClient(clientId);
    }

    public Optional<Client> changeStatus(ClientId clientId, ClientStatus status) {
        if (!store.updateClientStatus(clientId, status)) {
            return Optional.empty();
        }
        return store.findClient(clientId);
    }

    public Optional<IssuedApiKey> issueApiKey(ClientId clientId, Set<Scope> scopes, Optional<Instant> expiresAt) {
        if (store.findClient(clientId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(issue(clientId, ApiKeyToken.generate(random), scopes, expiresAt));
    }

    public Optional<List<ApiKey>> listApiKeys(ClientId clientId) {
        return store.findClient(clientId).map(client -> store.listApiKeys(clientId));
    }

    public boolean revokeApiKey(ClientId clientId, UUID apiKeyId) {
        return store.revokeApiKey(clientId, apiKeyId, now());
    }

    public boolean bootstrapAdminKey(ApiKeyToken token) {
        if (store.hasUsableAdminKey(now()) || store.findCredential(token.keyId()).isPresent()) {
            return false;
        }
        Client adminClient = store.findClientByName(ADMIN_CLIENT_NAME).orElseGet(this::createAdminClient);
        ApiKey apiKey = newApiKey(adminClient.id(), token, Set.of(Scope.ADMIN), Optional.empty());
        return store.insertApiKey(apiKey, hasher.hashWithActivePepper(token.secret()), hasher.activePepperId());
    }

    private Client createAdminClient() {
        Client client = new Client(new ClientId(ids.next()), ADMIN_CLIENT_NAME, ClientStatus.ACTIVE, now());
        if (store.insertClient(client)) {
            return client;
        }
        return store.findClientByName(ADMIN_CLIENT_NAME).orElseThrow(() -> new IllegalStateException(
                "admin client name is taken but the client cannot be read"));
    }

    private IssuedApiKey issue(ClientId clientId, ApiKeyToken token, Set<Scope> scopes, Optional<Instant> expiresAt) {
        ApiKey apiKey = newApiKey(clientId, token, scopes, expiresAt);
        if (!store.insertApiKey(apiKey, hasher.hashWithActivePepper(token.secret()), hasher.activePepperId())) {
            throw new IllegalStateException("generated API key id collided with an existing key");
        }
        return new IssuedApiKey(apiKey, token);
    }

    private ApiKey newApiKey(ClientId clientId, ApiKeyToken token, Set<Scope> scopes, Optional<Instant> expiresAt) {
        return new ApiKey(ids.next(), clientId, token.keyId(), scopes, ApiKeyStatus.ACTIVE, now(),
                expiresAt.map(instant -> instant.truncatedTo(ChronoUnit.MICROS)), Optional.empty(), Optional.empty());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
