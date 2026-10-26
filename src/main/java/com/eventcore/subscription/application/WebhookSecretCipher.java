package com.eventcore.subscription.application;

import com.eventcore.platform.crypto.KeyRing;
import com.eventcore.platform.crypto.KeyRingFormat;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import com.eventcore.subscription.domain.WebhookSecret;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnRole({Role.API, Role.WORKER})
public class WebhookSecretCipher {

    static final String TRANSFORMATION = "AES/GCM/NoPadding";
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    private static final KeyRingFormat KEYS = new KeyRingFormat("eventcore.security.webhook-secret-keys",
            "eventcore.security.active-webhook-secret-key", "AES", 32, 32);
    private static final String ASSOCIATED_DATA_SCOPE = "eventcore-webhook-secret\n";

    private final KeyRing keys;
    private final SecureRandom random = new SecureRandom();

    public WebhookSecretCipher(WebhookSecretProperties properties) {
        this.keys = KeyRing.parse(KEYS, properties.webhookSecretKeys(), properties.activeWebhookSecretKey());
    }

    public EncryptedSecret encrypt(WebhookSecret secret, UUID secretId) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.active(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(keys.activeId(), secretId));
            return new EncryptedSecret(keys.activeId(), nonce, cipher.doFinal(secret.key()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM is required by the Java platform", exception);
        }
    }

    static byte[] associatedData(String keyId, UUID secretId) {
        return (ASSOCIATED_DATA_SCOPE + keyId + "\n" + secretId).getBytes(StandardCharsets.UTF_8);
    }
}
