package com.eventcore.access.application;

import com.eventcore.access.domain.ApiKey;
import com.eventcore.access.domain.Client;
import com.eventcore.access.domain.ClientName;
import com.eventcore.access.domain.ClientStatus;
import com.eventcore.platform.identity.ClientId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessStore {

    boolean insertClient(Client client);

    Optional<Client> findClient(ClientId clientId);

    Optional<Client> findClientByName(ClientName name);

    boolean updateClientStatus(ClientId clientId, ClientStatus status);

    boolean insertApiKey(ApiKey apiKey, byte[] secretHash, String pepperId);

    Optional<StoredCredential> findCredential(String keyId);

    List<ApiKey> listApiKeys(ClientId clientId);

    boolean revokeApiKey(ClientId clientId, UUID apiKeyId, Instant revokedAt);

    void recordApiKeyUse(UUID apiKeyId, Instant usedAt);

    boolean hasUsableAdminKey(Instant now);
}
