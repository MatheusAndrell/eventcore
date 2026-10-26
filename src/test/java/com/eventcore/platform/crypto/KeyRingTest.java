package com.eventcore.platform.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyRingTest {

    private static final KeyRingFormat EXACT = new KeyRingFormat("keys", "active", "AES", 32, 32);
    private static final String KEY_A = Base64.getEncoder().encodeToString("a".repeat(32).getBytes());
    private static final String KEY_B = Base64.getEncoder().encodeToString("b".repeat(32).getBytes());

    @Test
    void exposesTheActiveKeyAndKeepsTheOthersAvailable() {
        KeyRing ring = KeyRing.parse(EXACT, List.of("k1:" + KEY_A, " k2 : " + KEY_B), "k2");

        assertThat(ring.activeId()).isEqualTo("k2");
        assertThat(ring.active().getEncoded()).isEqualTo("b".repeat(32).getBytes());
        assertThat(ring.active().getAlgorithm()).isEqualTo("AES");
        assertThat(ring.find("k1")).hasValueSatisfying(
                key -> assertThat(key.getEncoded()).isEqualTo("a".repeat(32).getBytes()));
        assertThat(ring.find("k3")).isEmpty();
    }

    @Test
    void requiresAtLeastOneKeyAndAConfiguredActiveKey() {
        assertThatThrownBy(() -> KeyRing.parse(EXACT, List.of(), "k1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("keys must define at least one key");
        assertThatThrownBy(() -> KeyRing.parse(EXACT, List.of("k1:" + KEY_A), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("active 'null' is not a configured key id");
        assertThatThrownBy(() -> KeyRing.parse(EXACT, List.of("k1:" + KEY_A), "k2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("active 'k2' is not a configured key id");
    }

    @Test
    void enforcesTheKeyLengthWithoutRevealingKeyMaterial() {
        String shortKey = Base64.getEncoder().encodeToString("short".getBytes());
        String longKey = Base64.getEncoder().encodeToString("c".repeat(33).getBytes());

        assertThatThrownBy(() -> KeyRing.parse(EXACT, List.of("k1:" + shortKey), "k1"))
                .hasMessage("key 'k1' in keys must have exactly 32 bytes");
        assertThatThrownBy(() -> KeyRing.parse(EXACT, List.of("k1:" + longKey), "k1"))
                .hasMessage("key 'k1' in keys must have exactly 32 bytes")
                .message().doesNotContain(longKey);
        assertThatThrownBy(() -> KeyRing.parse(new KeyRingFormat("keys", "active", "HmacSHA256", 32,
                Integer.MAX_VALUE), List.of("k1:" + shortKey), "k1"))
                .hasMessage("key 'k1' in keys must have at least 32 bytes");
    }

    @Test
    void rejectsMalformedAndDuplicatedEntries() {
        assertThatThrownBy(() -> KeyRing.parse(EXACT, List.of("k1:%%%"), "k1"))
                .hasMessage("key 'k1' in keys is not valid base64");
        assertThatThrownBy(() -> KeyRing.parse(EXACT, List.of(KEY_A), "k1"))
                .hasMessageContaining("'<id>:<base64>'");
        assertThatThrownBy(() -> KeyRing.parse(EXACT, List.of("K1:" + KEY_A), "K1"))
                .hasMessageContaining("'<id>:<base64>'");
        assertThatThrownBy(() -> KeyRing.parse(EXACT, List.of("k1:" + KEY_A, "k1:" + KEY_B), "k1"))
                .hasMessage("key id 'k1' is configured more than once in keys");
    }

    @Test
    void validatesTheFormatBounds() {
        assertThatThrownBy(() -> new KeyRingFormat("keys", "active", "AES", 0, 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyRingFormat("keys", "active", "AES", 32, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
