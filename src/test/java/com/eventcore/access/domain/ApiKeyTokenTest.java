package com.eventcore.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyTokenTest {

    private static final String VALID = "ec_abcdefghijklmnop_" + "A".repeat(40) + "-_9";

    @Test
    void generatesTokensThatRoundTripThroughParsing() {
        ApiKeyToken token = ApiKeyToken.generate(new SecureRandom());

        assertThat(token.value()).matches("ec_[a-z2-7]{16}_[A-Za-z0-9_-]{43}");
        assertThat(ApiKeyToken.parse(token.value())).contains(token);
    }

    @Test
    void generatesDistinctKeyIdsAndSecrets() {
        SecureRandom random = new SecureRandom();
        Set<String> keyIds = new HashSet<>();
        Set<String> secrets = new HashSet<>();
        for (int index = 0; index < 1_000; index++) {
            ApiKeyToken token = ApiKeyToken.generate(random);
            keyIds.add(token.keyId());
            secrets.add(token.secret());
        }

        assertThat(keyIds).hasSize(1_000);
        assertThat(secrets).hasSize(1_000);
    }

    @Test
    void parsesAWellFormedToken() {
        assertThat(ApiKeyToken.parse(VALID)).hasValueSatisfying(token -> {
            assertThat(token.keyId()).isEqualTo("abcdefghijklmnop");
            assertThat(token.secret()).isEqualTo("A".repeat(40) + "-_9");
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "ek_abcdefghijklmnop_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "ec_ABCDEFGHIJKLMNOP_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "ec_abcdefghijklmno1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "ec_abcdefghijklmnop-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "ec_abcdefghijklmnop_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "ec_abcdefghijklmnop_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "ec_abcdefghijklmnop_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+"
    })
    void rejectsMalformedTokens(String value) {
        assertThat(ApiKeyToken.parse(value)).isEmpty();
    }

    @Test
    void redactsTheSecretInItsTextualForm() {
        ApiKeyToken token = ApiKeyToken.parse(VALID).orElseThrow();

        assertThat(token.toString()).isEqualTo("ec_abcdefghijklmnop_<redacted>").doesNotContain(token.secret());
    }
}
