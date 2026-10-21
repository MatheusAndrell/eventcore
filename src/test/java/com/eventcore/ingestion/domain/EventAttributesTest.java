package com.eventcore.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EventAttributesTest {

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "42", "999999999"})
    void parsesPositiveDataVersions(String text) {
        assertThat(DataVersion.parse(text).value()).isEqualTo(Integer.parseInt(text));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "01", "-1", "1.0", " 1", "v1", "1000000000"})
    void rejectsInvalidDataVersions(String text) {
        assertThatThrownBy(() -> DataVersion.parse(text))
                .isInstanceOfSatisfying(InvalidEventAttributeException.class,
                        exception -> assertThat(exception.attribute()).isEqualTo("dataversion"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "order-42:retry.1", "0192f3c8-6f1b-7c39-9d0e-2b6c1f0a8e11", "A_Z.a-z:0"})
    void acceptsIdempotencyKeysWithinTheAllowedAlphabet(String value) {
        assertThat(new IdempotencyKey(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"has space", "slash/", "ação", "semi;colon"})
    void rejectsIdempotencyKeysOutsideTheAllowedAlphabet(String value) {
        assertThatThrownBy(() -> new IdempotencyKey(value)).isInstanceOf(InvalidEventAttributeException.class);
    }

    @Test
    void limitsIdempotencyKeysTo128Characters() {
        assertThat(new IdempotencyKey("k".repeat(128)).value()).hasSize(128);
        assertThatThrownBy(() -> new IdempotencyKey("k".repeat(129))).isInstanceOf(InvalidEventAttributeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"urn:acme:orders", "https://acme.example/orders", "/orders", "orders"})
    void acceptsUriReferencesAsSource(String value) {
        assertThat(new EventSource(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "has space", "bad^char"})
    void rejectsSourcesThatAreNotUriReferences(String value) {
        assertThatThrownBy(() -> new EventSource(value)).isInstanceOf(InvalidEventAttributeException.class);
    }

    @Test
    void limitsTextualAttributes() {
        assertThatThrownBy(() -> new EventType("t".repeat(EventType.MAX_LENGTH + 1)))
                .isInstanceOf(InvalidEventAttributeException.class);
        assertThatThrownBy(() -> new EventSource("s".repeat(EventSource.MAX_LENGTH + 1)))
                .isInstanceOf(InvalidEventAttributeException.class);
        assertThatThrownBy(() -> new EventSubject("s".repeat(EventSubject.MAX_LENGTH + 1)))
                .isInstanceOf(InvalidEventAttributeException.class);
        assertThat(new EventSubject("s".repeat(EventSubject.MAX_LENGTH)).value()).hasSize(EventSubject.MAX_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"text\"", "1", ""})
    void requiresDataToBeAJsonObject(String json) {
        assertThatThrownBy(() -> new EventData(json)).isInstanceOf(InvalidEventAttributeException.class);
    }

    @Test
    void comparesFingerprintsByContent() {
        byte[] bytes = new byte[RequestFingerprint.LENGTH];
        bytes[0] = 1;
        RequestFingerprint fingerprint = new RequestFingerprint(bytes);
        bytes[0] = 2;

        assertThat(fingerprint).isEqualTo(new RequestFingerprint(fingerprint.bytes()));
        assertThat(fingerprint.bytes()[0]).isEqualTo((byte) 1);
        assertThat(fingerprint).isNotEqualTo(new RequestFingerprint(bytes));
    }
}
