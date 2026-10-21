package com.eventcore.access.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_PEPPER_BYTES = 32;
    private static final Pattern PEPPER_ID = Pattern.compile("[a-z0-9]{1,16}");

    private final Map<String, SecretKeySpec> peppers;
    private final String activePepperId;

    public ApiKeyHasher(ApiKeyProperties properties) {
        this.peppers = parse(properties);
        this.activePepperId = properties.activeApiKeyPepper();
        if (!peppers.containsKey(activePepperId)) {
            throw new IllegalStateException(
                    "eventcore.security.active-api-key-pepper '" + activePepperId + "' is not a configured pepper id");
        }
    }

    public String activePepperId() {
        return activePepperId;
    }

    public byte[] hashWithActivePepper(String secret) {
        return hmac(peppers.get(activePepperId), secret);
    }

    public Optional<byte[]> hash(String pepperId, String secret) {
        return Optional.ofNullable(peppers.get(pepperId)).map(pepper -> hmac(pepper, secret));
    }

    private static Map<String, SecretKeySpec> parse(ApiKeyProperties properties) {
        Map<String, SecretKeySpec> parsed = new HashMap<>();
        for (String entry : properties.apiKeyPeppers()) {
            int separator = entry.indexOf(':');
            String id = separator < 0 ? "" : entry.substring(0, separator).strip();
            if (!PEPPER_ID.matcher(id).matches()) {
                throw new IllegalStateException(
                        "each eventcore.security.api-key-peppers entry must be '<id>:<base64>' with id in [a-z0-9]{1,16}");
            }
            byte[] key = decode(id, entry.substring(separator + 1).strip());
            if (parsed.putIfAbsent(id, new SecretKeySpec(key, ALGORITHM)) != null) {
                throw new IllegalStateException("pepper id '" + id + "' is configured more than once");
            }
        }
        return Map.copyOf(parsed);
    }

    private static byte[] decode(String id, String encoded) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("pepper '" + id + "' is not valid base64");
        }
        if (key.length < MIN_PEPPER_BYTES) {
            throw new IllegalStateException("pepper '" + id + "' must have at least " + MIN_PEPPER_BYTES + " bytes");
        }
        return key;
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
