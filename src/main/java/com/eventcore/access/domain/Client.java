package com.eventcore.access.domain;

import com.eventcore.platform.identity.ClientId;
import java.time.Instant;
import java.util.Objects;

public record Client(ClientId id, ClientName name, ClientStatus status, Instant createdAt) {

    public Client {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
