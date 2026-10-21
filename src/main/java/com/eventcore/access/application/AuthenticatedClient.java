package com.eventcore.access.application;

import com.eventcore.access.domain.Scope;
import com.eventcore.platform.identity.ClientId;
import java.util.Objects;
import java.util.Set;

public record AuthenticatedClient(ClientId clientId, String keyId, Set<Scope> scopes) {

    public AuthenticatedClient {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(keyId, "keyId");
        scopes = Set.copyOf(scopes);
    }
}
