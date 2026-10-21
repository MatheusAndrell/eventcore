package com.eventcore.access.application;

import com.eventcore.access.domain.ApiKey;
import com.eventcore.access.domain.ClientStatus;
import java.util.Arrays;
import java.util.Objects;

public record StoredCredential(ApiKey apiKey, byte[] secretHash, String pepperId, ClientStatus clientStatus) {

    public StoredCredential {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(pepperId, "pepperId");
        Objects.requireNonNull(clientStatus, "clientStatus");
        secretHash = secretHash.clone();
    }

    @Override
    public byte[] secretHash() {
        return secretHash.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoredCredential credential
                && apiKey.equals(credential.apiKey)
                && Arrays.equals(secretHash, credential.secretHash)
                && pepperId.equals(credential.pepperId)
                && clientStatus == credential.clientStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(apiKey, Arrays.hashCode(secretHash), pepperId, clientStatus);
    }

    @Override
    public String toString() {
        return "StoredCredential[keyId=" + apiKey.keyId() + ", pepperId=" + pepperId + "]";
    }
}
