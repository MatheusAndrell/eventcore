package com.eventcore.platform.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JsonCanonicalizerTest {

    private final StrictJsonReader reader = new StrictJsonReader();
    private final JsonCanonicalizer canonicalizer = new JsonCanonicalizer();

    @Test
    void canonicalizesTheRfc8785SampleDocument() {
        String input = """
                {
                  "numbers": [333333333.3333333, 1E30, 4.50, 2e-3, 0.000000000000000000000000001],
                  "string": "\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\"\\/",
                  "literals": [null, true, false]
                }
                """;

        assertThat(canonical(input)).isEqualTo(
                "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                        + "\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}");
    }

    @Test
    void sortsPropertiesByUtf16CodeUnits() {
        String input = """
                {
                  "\\u20ac": "Euro Sign",
                  "\\r": "Carriage Return",
                  "\\ufb33": "Hebrew Letter Dalet With Dagesh",
                  "1": "One",
                  "\\ud83d\\ude00": "Emoji: Grinning Face",
                  "\\u0080": "Control",
                  "\\u00f6": "Latin Small Letter O With Diaeresis"
                }
                """;

        assertThat(canonical(input)).containsSubsequence(
                "Carriage Return", "One", "Control", "Latin Small Letter O With Diaeresis",
                "Euro Sign", "Emoji: Grinning Face", "Hebrew Letter Dalet With Dagesh");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"b\":1,\"a\":2}|{ \"a\" : 2 , \"b\" : 1 }",
            "{\"amount\":1.0}|{\"amount\":1}",
            "{\"amount\":1e2}|{\"amount\":100}",
            "{\"amount\":0.10}|{\"amount\":0.1}",
            "{\"name\":\"\\u00e9\"}|{\"name\":\"é\"}",
            "{\"nested\":{\"y\":[1,2],\"x\":null}}|{\"nested\":{\"x\":null,\"y\":[1,2]}}"
    })
    void producesIdenticalOutputForEquivalentDocuments(String left, String right) {
        assertThat(canonicalizer.canonicalize(reader.read(bytes(left))))
                .isEqualTo(canonicalizer.canonicalize(reader.read(bytes(right))));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"amount\":1}|{\"amount\":\"1\"}",
            "{\"items\":[1,2]}|{\"items\":[2,1]}",
            "{\"name\":\"\\u00e9\"}|{\"name\":\"e\\u0301\"}",
            "{\"value\":null}|{}"
    })
    void distinguishesDifferentDocuments(String left, String right) {
        assertThat(canonicalizer.canonicalize(reader.read(bytes(left))))
                .isNotEqualTo(canonicalizer.canonicalize(reader.read(bytes(right))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"9007199254740993", "1e400", "1e-400", "333333333.33333329", "0.30000000000000001"})
    void rejectsNumbersWhoseValueIsNotPreservedByDoublePrecision(String number) {
        assertThatThrownBy(() -> canonical("{\"value\":" + number + "}"))
                .isInstanceOfSatisfying(CanonicalizationException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(CanonicalizationException.Reason.UNREPRESENTABLE_NUMBER);
                    assertThat(exception.pointer()).isEqualTo("/value");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.1", "9007199254740992", "1.7976931348623157e308", "5e-324", "-0"})
    void acceptsNumbersThatRoundTripThroughDoublePrecision(String number) {
        assertThat(canonical("{\"value\":" + number + "}")).startsWith("{\"value\":");
    }

    @Test
    void reportsTheJsonPointerOfTheOffendingValue() {
        assertThatThrownBy(() -> canonical("{\"a\":[1,{\"b/c~d\":9007199254740993}]}"))
                .isInstanceOfSatisfying(CanonicalizationException.class,
                        exception -> assertThat(exception.pointer()).isEqualTo("/a/1/b~1c~0d"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"value\":\"\\ud800\"}", "{\"value\":\"\\udc00x\"}", "{\"value\":[\"ok\",\"\\ud800\"]}"})
    void rejectsUnpairedSurrogatesInValues(String input) {
        assertThatThrownBy(() -> canonical(input))
                .isInstanceOfSatisfying(CanonicalizationException.class, exception -> assertThat(exception.reason())
                        .isEqualTo(CanonicalizationException.Reason.UNPAIRED_SURROGATE));
    }

    @Test
    void neverSeesUnpairedSurrogatesInNamesBecauseTheReaderRejectsThem() {
        assertThatThrownBy(() -> canonical("{\"\\ud800\":1}")).isInstanceOf(InvalidJsonException.class);
    }

    @Test
    void preservesSurrogatePairs() {
        assertThat(canonical("{\"value\":\"\\ud83d\\ude00\"}")).isEqualTo("{\"value\":\"\uD83D\uDE00\"}");
    }

    @Test
    void escapesControlCharactersWithLowercaseHexadecimal() {
        assertThat(canonical("{\"value\":\"\\u001f\\u0007\\b\\t\\u007f\"}"))
                .isEqualTo("{\"value\":\"\\u001f\\u0007\\b\\t\u007f\"}");
    }

    private String canonical(String json) {
        return canonicalizer.canonicalText(reader.read(bytes(json)));
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
