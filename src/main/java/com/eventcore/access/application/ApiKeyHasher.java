package com.eventcore.access.application;

import com.eventcore.platform.crypto.KeyRing;
import com.eventcore.platform.crypto.KeyRingFormat;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final KeyRingFormat PEPPERS = new KeyRingFormat("eventcore.security.api-key-peppers",
            "eventcore.security.active-api-key-pepper", ALGORITHM, 32, Integer.MAX_VALUE);

    private final KeyRing peppers;

    public ApiKeyHasher(ApiKeyProperties properties) {
        this.peppers = KeyRing.parse(PEPPERS, properties.apiKeyPeppers(), properties.activeApiKeyPepper());
    }

    public String activePepperId() {
        return peppers.activeId();
    }

    public byte[] hashWithActivePepper(String secret) {
        return hmac(peppers.active(), secret);
    }

    public Optional<byte[]> hash(String pepperId, String secret) {
        return peppers.find(pepperId).map(pepper -> hmac(pepper, secret));
    }

    private static byte[] hmac(SecretKeySpec pepper, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(pepper);
            return mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is required by the Java platform", exception);
        }
    }
}
