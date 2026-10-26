package com.eventcore.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventcore.platform.identity.ClientId;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SubscriptionAttributesTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-20T12:00:00Z");

    @Test
    void acceptsHttpsEndpointsAndNormalizesTheScheme() {
        assertThat(WebhookEndpoint.parse("https://hooks.acme.example/events?tenant=1").toString())
                .isEqualTo("https://hooks.acme.example/events?tenant=1");
        assertThat(WebhookEndpoint.parse("HTTPS://hooks.acme.example:8443/events").toString())
                .isEqualTo("https://hooks.acme.example:8443/events");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "http://hooks.acme.example/events|url: must be an absolute https URL",
            "/events|url: must be an absolute https URL",
            "ftp://hooks.acme.example/events|url: must be an absolute https URL",
            "https:hooks.acme.example|url: must have a valid host name",
            "https://bad_host.acme.example/events|url: must have a valid host name",
            "https://user:secret@hooks.acme.example/events|url: must not contain user information",
            "https://hooks.acme.example/events#fragment|url: must not contain a fragment",
            "https://hooks.acme.example:0/events|url: must use a port between 1 and 65535",
            "https://hooks.acme.example:70000/events|url: must use a port between 1 and 65535",
            "https://hooks.acme.example/a b|url: must be a valid URL"})
    void rejectsEndpointsThatAreNotPlainHttpsUrls(String url, String message) {
        assertThatThrownBy(() -> WebhookEndpoint.parse(url))
                .isInstanceOf(InvalidSubscriptionException.class)
                .hasMessage(message);
    }

    @Test
    void limitsTheEndpointLength() {
        String prefix = "https://hooks.acme.example/";
        String longest = prefix + "a".repeat(WebhookEndpoint.MAX_LENGTH - prefix.length());

        assertThat(WebhookEndpoint.parse(longest).toString()).hasSize(WebhookEndpoint.MAX_LENGTH);
        assertThatThrownBy(() -> WebhookEndpoint.parse(longest + "a"))
                .hasMessage("url: must have at most 2048 characters");
        assertThatThrownBy(() -> WebhookEndpoint.parse(null))
                .isInstanceOf(InvalidSubscriptionException.class);
    }

    @Test
    void sortsAndDeduplicatesEventTypes() {
        assertThat(SubscribedEventTypes.of(List.of("payment.captured", "order.placed", "order.placed")).values())
                .containsExactly("order.placed", "payment.captured");
    }

    @Test
    void boundsTheSubscribedEventTypes() {
        List<String> tooMany = IntStream.rangeClosed(0, SubscribedEventTypes.MAX_TYPES)
                .mapToObj(index -> "type." + index).toList();

        assertThatThrownBy(() -> SubscribedEventTypes.of(List.of()))
                .hasMessage("eventTypes: must contain between 1 and 50 event types");
        assertThatThrownBy(() -> SubscribedEventTypes.of(tooMany))
                .hasMessage("eventTypes: must contain between 1 and 50 event types");
        assertThatThrownBy(() -> SubscribedEventTypes.of(List.of(" ")))
                .hasMessage("eventTypes: each event type must have between 1 and 128 characters");
        assertThatThrownBy(() -> SubscribedEventTypes.of(List.of("a".repeat(129))))
                .hasMessage("eventTypes: each event type must have between 1 and 128 characters");
        assertThatThrownBy(() -> SubscribedEventTypes.of(Arrays.asList("order.placed", null)))
                .hasMessage("eventTypes: must not contain null event types");
    }

    @Test
    void generatesStandardWebhooksSecretsThatNeverPrintTheirKey() {
        WebhookSecret secret = WebhookSecret.generate(new SecureRandom());

        assertThat(secret.value()).matches("whsec_[A-Za-z0-9+/]{43}=");
        assertThat(Base64.getDecoder().decode(secret.value().substring(6))).isEqualTo(secret.key());
        assertThat(secret.key()).hasSize(32);
        assertThat(secret).hasToString("whsec_<redacted>");
        assertThat(WebhookSecret.generate(new SecureRandom()).value()).isNotEqualTo(secret.value());
    }

    @Test
    void appliesOnlyTheRequestedChanges() {
        Subscription subscription = Subscription.create(UUID.randomUUID(), new ClientId(UUID.randomUUID()),
                WebhookEndpoint.parse("https://hooks.acme.example/a"), SubscribedEventTypes.of(List.of("order.placed")),
                CREATED_AT);

        Subscription paused = subscription.apply(new SubscriptionChanges(Optional.empty(), Optional.empty(),
                Optional.of(SubscriptionStatus.PAUSED)), CREATED_AT.plusSeconds(60));
        Subscription moved = subscription.apply(new SubscriptionChanges(
                Optional.of(WebhookEndpoint.parse("https://hooks.acme.example/b")), Optional.empty(),
                Optional.empty()), CREATED_AT.minusSeconds(5));

        assertThat(subscription.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(paused.status()).isEqualTo(SubscriptionStatus.PAUSED);
        assertThat(paused.endpoint()).isEqualTo(subscription.endpoint());
        assertThat(paused.updatedAt()).isEqualTo(CREATED_AT.plusSeconds(60));
        assertThat(moved.endpoint().toString()).isEqualTo("https://hooks.acme.example/b");
        assertThat(moved.updatedAt()).isEqualTo(CREATED_AT);
        assertThatThrownBy(() -> new SubscriptionChanges(Optional.empty(), Optional.empty(), Optional.empty()))
                .hasMessage("request: must change url, eventTypes or status");
    }
}
