package com.eventcore.subscription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventcore.subscription.domain.WebhookSecret;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookSecretCipherTest {

    private static final byte[] KEY_1 = "1".repeat(32).getBytes();
    private static final byte[] KEY_2 = "2".repeat(32).getBytes();

    private final WebhookSecretCipher cipher = cipher("k2");

    @Test
    void encryptsWithTheActiveKeyUsingAuthenticatedEncryption() throws GeneralSecurityException {
        WebhookSecret secret = WebhookSecret.generate(new SecureRandom());
        UUID secretId = UUID.randomUUID();

        EncryptedSecret encrypted = cipher.encrypt(secret, secretId);

        assertThat(encrypted.keyId()).isEqualTo("k2");
        assertThat(encrypted.nonce()).hasSize(WebhookSecretCipher.NONCE_BYTES);
        assertThat(encrypted.ciphertext()).hasSize(WebhookSecret.KEY_BYTES + WebhookSecretCipher.TAG_BITS / 8)
                .isNotEqualTo(secret.key());
        assertThat(decrypt(KEY_2, encrypted, "k2", secretId)).isEqualTo(secret.key());
    }

    @Test
    void bindsTheCiphertextToItsSecretIdAndKeyId() {
        EncryptedSecret encrypted = cipher.encrypt(WebhookSecret.generate(new SecureRandom()), UUID.randomUUID());

        assertThatThrownBy(() -> decrypt(KEY_2, encrypted, "k2", UUID.randomUUID()))
                .isInstanceOf(AEADBadTagException.class);
        assertThatThrownBy(() -> decrypt(KEY_2, encrypted, "k1", UUID.randomUUID()))
                .isInstanceOf(AEADBadTagException.class);
        assertThatThrownBy(() -> decrypt(KEY_1, encrypted, "k2", UUID.randomUUID()))
                .isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void neverReusesANonce() {
        WebhookSecret secret = WebhookSecret.generate(new SecureRandom());
        UUID secretId = UUID.randomUUID();

        EncryptedSecret first = cipher.encrypt(secret, secretId);
        EncryptedSecret second = cipher.encrypt(secret, secretId);

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    void requiresAes256KeysAndAnActiveKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new WebhookSecretCipher(new WebhookSecretProperties(List.of(), null)))
                .hasMessage("eventcore.security.webhook-secret-keys must define at least one key");
        assertThatThrownBy(() -> new WebhookSecretCipher(new WebhookSecretProperties(List.of("k1:" + shortKey), "k1")))
                .hasMessage("key 'k1' in eventcore.security.webhook-secret-keys must have exactly 32 bytes");
        assertThatThrownBy(() -> cipher("k3"))
                .hasMessage("eventcore.security.active-webhook-secret-key 'k3' is not a configured key id");
    }

    @Test
    void doesNotPrintKeyMaterial() {
        WebhookSecretProperties properties = properties("k1");

        assertThat(properties.toString()).doesNotContain(properties.webhookSecretKeys().getFirst().substring(3))
                .contains("activeWebhookSecretKey=k1");
    }

    private static byte[] decrypt(byte[] key, EncryptedSecret encrypted, String keyId, UUID secretId)
            throws GeneralSecurityException {
        Cipher decryptor = Cipher.getInstance(WebhookSecretCipher.TRANSFORMATION);
        decryptor.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(WebhookSecretCipher.TAG_BITS, encrypted.nonce()));
        decryptor.updateAAD(WebhookSecretCipher.associatedData(keyId, secretId));
        return decryptor.doFinal(encrypted.ciphertext());
    }

    private static WebhookSecretCipher cipher(String active) {
        return new WebhookSecretCipher(properties(active));
    }

    private static WebhookSecretProperties properties(String active) {
        return new WebhookSecretProperties(List.of(
                "k1:" + Base64.getEncoder().encodeToString(KEY_1),
                "k2:" + Base64.getEncoder().encodeToString(KEY_2)), active);
    }
}
