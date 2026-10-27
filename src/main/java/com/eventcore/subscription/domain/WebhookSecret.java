package com.eventcore.subscription.domain;

import java.util.Base64;
import java.util.random.RandomGenerator;

public final class WebhookSecret {

    public static final String PREFIX = "whsec_";
    public static final int KEY_BYTES = 32;

    private final byte[] key;

    private WebhookSecret(byte[] key) {
        this.key = key;
    }

    public static WebhookSecret generate(RandomGenerator random) {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        return new WebhookSecret(key);
    }

    public static WebhookSecret of(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("webhook secrets must have " + KEY_BYTES + " bytes");
        }
        return new WebhookSecret(key.clone());
    }

    public byte[] key() {
        return key.clone();
    }

    public String value() {
        return PREFIX + Base64.getEncoder().encodeToString(key);
    }

    @Override
    public String toString() {
        return PREFIX + "<redacted>";
    }
}
