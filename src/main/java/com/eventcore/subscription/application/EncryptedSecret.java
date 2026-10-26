package com.eventcore.subscription.application;

import java.util.Objects;

public record EncryptedSecret(String keyId, byte[] nonce, byte[] ciphertext) {

    public EncryptedSecret {
        Objects.requireNonNull(keyId, "keyId");
        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }
}
