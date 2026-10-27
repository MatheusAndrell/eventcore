package com.eventcore.delivery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandardWebhookSignatureTest {

    private static final byte[] SPECIFICATION_KEY = Base64.getDecoder().decode("MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
    private static final byte[] SPECIFICATION_BODY = "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8);

    @Test
    void matchesTheStandardWebhooksReferenceSignature() {
        String signature = StandardWebhookSignature.sign("msg_p5jXN8AQM9LWM0D4loKWxJek", 1614265330L,
                SPECIFICATION_BODY, List.of(SPECIFICATION_KEY));

        assertThat(signature).isEqualTo("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
    }

    @Test
    void signsWithEveryKeySeparatedBySpaces() {
        byte[] otherKey = "o".repeat(32).getBytes(StandardCharsets.UTF_8);

        String signature = StandardWebhookSignature.sign("msg_p5jXN8AQM9LWM0D4loKWxJek", 1614265330L,
                SPECIFICATION_BODY, List.of(SPECIFICATION_KEY, otherKey));

        assertThat(signature.split(" ")).hasSize(2)
                .startsWith("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=")
                .allSatisfy(part -> assertThat(part).matches("v1,[A-Za-z0-9+/]{43}="));
    }

    @Test
    void bindsTheSignatureToTheIdTimestampAndBody() {
        List<byte[]> keys = List.of(SPECIFICATION_KEY);
        String reference = StandardWebhookSignature.sign("msg_1", 1614265330L, SPECIFICATION_BODY, keys);

        assertThat(StandardWebhookSignature.sign("msg_2", 1614265330L, SPECIFICATION_BODY, keys))
                .isNotEqualTo(reference);
        assertThat(StandardWebhookSignature.sign("msg_1", 1614265331L, SPECIFICATION_BODY, keys))
                .isNotEqualTo(reference);
        assertThat(StandardWebhookSignature.sign("msg_1", 1614265330L, "{}".getBytes(StandardCharsets.UTF_8), keys))
                .isNotEqualTo(reference);
        assertThatThrownBy(() -> StandardWebhookSignature.sign("msg_1", 1L, SPECIFICATION_BODY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
