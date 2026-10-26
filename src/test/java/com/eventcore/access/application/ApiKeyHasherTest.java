package com.eventcore.access.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiKeyHasherTest {

    private static final String PEPPER_A = Base64.getEncoder().encodeToString("a".repeat(32).getBytes());
    private static final String PEPPER_B = Base64.getEncoder().encodeToString("b".repeat(32).getBytes());

    @Test
    void hashesDeterministicallyWithTheActivePepper() {
        ApiKeyHasher hasher = hasher(List.of("p1:" + PEPPER_A, "p2:" + PEPPER_B), "p1");

        assertThat(hasher.activePepperId()).isEqualTo("p1");
        assertThat(hasher.hashWithActivePepper("secret")).hasSize(32)
                .isEqualTo(hasher.hashWithActivePepper("secret"))
                .isNotEqualTo(hasher.hashWithActivePepper("secret2"));
    }

    @Test
    void keepsOlderPeppersAvailableForVerification() {
        ApiKeyHasher hasher = hasher(List.of("p1:" + PEPPER_A, "p2:" + PEPPER_B), "p2");

        assertThat(hasher.hash("p1", "secret")).hasValueSatisfying(
                hash -> assertThat(hash).isNotEqualTo(hasher.hashWithActivePepper("secret")));
        assertThat(hasher.hash("p3", "secret")).isEmpty();
    }

    @Test
    void requiresTheActivePepperToBeConfigured() {
        assertThatThrownBy(() -> hasher(List.of("p1:" + PEPPER_A), "p2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eventcore.security.active-api-key-pepper 'p2' is not a configured key id");
    }

    @Test
    void rejectsShortOrMalformedPeppersWithoutRevealingThem() {
        String shortPepper = Base64.getEncoder().encodeToString("short".getBytes());

        assertThatThrownBy(() -> hasher(List.of("p1:" + shortPepper), "p1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes")
                .message().doesNotContain(shortPepper);
        assertThatThrownBy(() -> hasher(List.of("p1:%%%"), "p1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid base64");
        assertThatThrownBy(() -> hasher(List.of("P1:" + PEPPER_A), "P1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("<id>:<base64>");
        assertThatThrownBy(() -> hasher(List.of("p1:" + PEPPER_A, "p1:" + PEPPER_B), "p1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than once");
    }

    private static ApiKeyHasher hasher(List<String> peppers, String active) {
        return new ApiKeyHasher(new ApiKeyProperties(peppers, active, null));
    }
}
