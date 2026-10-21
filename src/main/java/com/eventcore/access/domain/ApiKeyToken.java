package com.eventcore.access.domain;

import java.util.Base64;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

public record ApiKeyToken(String keyId, String secret) {

    private static final String PREFIX = "ec_";
    private static final char SEPARATOR = '_';
    private static final int KEY_ID_BYTES = 10;
    private static final int SECRET_BYTES = 32;
    private static final Pattern KEY_ID = Pattern.compile("[a-z2-7]{16}");
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final int TOKEN_LENGTH = PREFIX.length() + 16 + 1 + 43;
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    public ApiKeyToken {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("key id must have 16 lowercase base32 characters");
        }
        if (secret == null || !SECRET.matcher(secret).matches()) {
            throw new IllegalArgumentException("secret must have 43 base64url characters");
        }
    }

    public static ApiKeyToken generate(RandomGenerator random) {
        byte[] keyId = new byte[KEY_ID_BYTES];
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(keyId);
        random.nextBytes(secret);
        return new ApiKeyToken(base32(keyId), Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    }

    public static Optional<ApiKeyToken> parse(String token) {
        if (token == null || token.length() != TOKEN_LENGTH || !token.startsWith(PREFIX)
                || token.charAt(PREFIX.length() + 16) != SEPARATOR) {
            return Optional.empty();
        }
        String keyId = token.substring(PREFIX.length(), PREFIX.length() + 16);
        String secret = token.substring(PREFIX.length() + 17);
        if (!KEY_ID.matcher(keyId).matches() || !SECRET.matcher(secret).matches()) {
            return Optional.empty();
        }
        return Optional.of(new ApiKeyToken(keyId, secret));
    }

    public String value() {
        return PREFIX + keyId + SEPARATOR + secret;
    }

    @Override
    public String toString() {
        return PREFIX + keyId + SEPARATOR + "<redacted>";
    }

    private static String base32(byte[] bytes) {
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte current : bytes) {
            buffer = (buffer << 8) | (current & 0xFF);
            bits += 8;
            while (bits >= 5) {
                encoded.append(BASE32[(buffer >>> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        return encoded.toString();
    }
}
