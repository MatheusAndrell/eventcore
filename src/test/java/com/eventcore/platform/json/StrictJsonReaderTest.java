package com.eventcore.platform.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class StrictJsonReaderTest {

    private final StrictJsonReader reader = new StrictJsonReader();

    @Test
    void readsAValidDocumentPreservingDecimalPrecision() {
        JsonNode node = read("{\"amount\": 10.10, \"items\": [1, 2]}");

        assertThat(node.get("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("10.10"));
        assertThat(node.get("items").size()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"a\": 1, \"a\": 2}",
            "{} {}",
            "{\"a\": 1} trailing",
            "{\"a\": 1 // comment\n}",
            "{\"a\": NaN}",
            "{'a': 1}",
            "{\"a\": [1,]}",
            "{a: 1}",
            "{\"a\": 01}",
            "",
            "   "
    })
    void rejectsNonStrictJson(String input) {
        assertThatThrownBy(() -> read(input)).isInstanceOf(InvalidJsonException.class);
    }

    @Test
    void rejectsDocumentsNestedDeeperThanTheLimit() {
        String tooDeep = "[".repeat(StrictJsonReader.MAX_NESTING_DEPTH + 1)
                + "]".repeat(StrictJsonReader.MAX_NESTING_DEPTH + 1);

        assertThatThrownBy(() -> read(tooDeep)).isInstanceOf(InvalidJsonException.class);
    }

    @Test
    void acceptsDocumentsAtTheNestingLimit() {
        String atLimit = "[".repeat(StrictJsonReader.MAX_NESTING_DEPTH) + "]".repeat(StrictJsonReader.MAX_NESTING_DEPTH);

        assertThat(read(atLimit).isArray()).isTrue();
    }

    @Test
    void rejectsNumbersLongerThanTheLimit() {
        String longNumber = "{\"a\": " + "1".repeat(StrictJsonReader.MAX_NUMBER_LENGTH + 1) + "}";

        assertThatThrownBy(() -> read(longNumber)).isInstanceOf(InvalidJsonException.class);
    }

    @Test
    void doesNotEchoTheDocumentInErrorMessages() {
        assertThatThrownBy(() -> read("{\"secret\": \"s3cr3t-value\", \"secret\": 1}"))
                .isInstanceOf(InvalidJsonException.class)
                .message()
                .doesNotContain("s3cr3t-value");
    }

    private JsonNode read(String json) {
        return reader.read(json.getBytes(StandardCharsets.UTF_8));
    }
}
