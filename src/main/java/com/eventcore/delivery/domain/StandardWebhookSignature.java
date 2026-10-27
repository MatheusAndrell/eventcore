package com.eventcore.delivery.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class StandardWebhookSignature {

    public static final String ID_HEADER = "webhook-id";
    public static final String TIMESTAMP_HEADER = "webhook-timestamp";
    public static final String SIGNATURE_HEADER = "webhook-signature";
    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";

    private StandardWebhookSignature() {
    }

    public static String sign(String messageId, long timestamp, byte[] body, List<byte[]> keys) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("at least one signing key is required");
        }
        byte[] prefix = (messageId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8);
        return keys.stream()
                .map(key -> VERSION + "," + Base64.getEncoder().encodeToString(hmac(key, prefix, body)))
                .collect(Collectors.joining(" "));
    }

    private static byte[] hmac(byte[] key, byte[] prefix, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            mac.update(prefix);
            return mac.doFinal(body);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is required by the Java platform", exception);
        }
    }
}
