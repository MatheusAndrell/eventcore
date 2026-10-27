package com.eventcore.subscription.application;

import java.util.Objects;
import java.util.UUID;

public record StoredSecret(UUID id, EncryptedSecret secret) {

    public StoredSecret {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(secret, "secret");
    }
}
