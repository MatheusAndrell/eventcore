package com.eventcore.platform.crypto;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.spec.SecretKeySpec;

public final class KeyRing {

    private static final Pattern KEY_ID = Pattern.compile("[a-z0-9]{1,16}");

    private final Map<String, SecretKeySpec> keys;
    private final String activeId;

    private KeyRing(Map<String, SecretKeySpec> keys, String activeId) {
        this.keys = Map.copyOf(keys);
        this.activeId = activeId;
    }

    public static KeyRing parse(KeyRingFormat format, List<String> entries, String activeId) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalStateException(format.entriesProperty() + " must define at least one key");
        }
        Map<String, SecretKeySpec> keys = new HashMap<>();
        for (String entry : entries) {
            int separator = entry.indexOf(':');
            String id = separator < 0 ? "" : entry.substring(0, separator).strip();
            if (!KEY_ID.matcher(id).matches()) {
                throw new IllegalStateException("each " + format.entriesProperty()
                        + " entry must be '<id>:<base64>' with id in [a-z0-9]{1,16}");
            }
            byte[] key = decode(format, id, entry.substring(separator + 1).strip());
            if (keys.putIfAbsent(id, new SecretKeySpec(key, format.algorithm())) != null) {
                throw new IllegalStateException(
                        "key id '" + id + "' is configured more than once in " + format.entriesProperty());
            }
        }
        if (activeId == null || !keys.containsKey(activeId)) {
            throw new IllegalStateException(
                    format.activeProperty() + " '" + activeId + "' is not a configured key id");
        }
        return new KeyRing(keys, activeId);
    }

    public String activeId() {
        return activeId;
    }

    public SecretKeySpec active() {
        return keys.get(activeId);
    }

    public Optional<SecretKeySpec> find(String id) {
        return Optional.ofNullable(keys.get(id));
    }

    private static byte[] decode(KeyRingFormat format, String id, String encoded) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "key '" + id + "' in " + format.entriesProperty() + " is not valid base64");
        }
        if (!format.acceptsLength(key.length)) {
            throw new IllegalStateException(
                    "key '" + id + "' in " + format.entriesProperty() + " must have " + format.lengthRequirement());
        }
        return key;
    }
}
