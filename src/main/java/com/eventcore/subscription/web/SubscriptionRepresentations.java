package com.eventcore.subscription.web;

import com.eventcore.subscription.application.CreatedSubscription;
import com.eventcore.subscription.application.RotatedSecret;
import com.eventcore.subscription.domain.Subscription;
import com.eventcore.subscription.domain.SubscriptionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

final class SubscriptionRepresentations {

    private SubscriptionRepresentations() {
    }

    record CreateSubscriptionRequest(@NotBlank String url, @NotEmpty List<@NotBlank String> eventTypes) {
    }

    record ChangeSubscriptionRequest(String url, List<@NotBlank String> eventTypes, SubscriptionStatus status) {
    }

    record SubscriptionResponse(
            String id, String url, List<String> eventTypes, String status, Instant createdAt, Instant updatedAt) {

        static SubscriptionResponse of(Subscription subscription) {
            return new SubscriptionResponse(subscription.id().toString(), subscription.endpoint().toString(),
                    List.copyOf(subscription.eventTypes().values()), subscription.status().name(),
                    subscription.createdAt(), subscription.updatedAt());
        }
    }

    record CreatedSubscriptionResponse(
            String id,
            String url,
            List<String> eventTypes,
            String status,
            Instant createdAt,
            Instant updatedAt,
            String secret) {

        static CreatedSubscriptionResponse of(CreatedSubscription created) {
            SubscriptionResponse subscription = SubscriptionResponse.of(created.subscription());
            return new CreatedSubscriptionResponse(subscription.id(), subscription.url(), subscription.eventTypes(),
                    subscription.status(), subscription.createdAt(), subscription.updatedAt(),
                    created.secret().value());
        }
    }

    record RotatedSecretResponse(String secret, Instant previousSecretExpiresAt) {

        static RotatedSecretResponse of(RotatedSecret rotated) {
            return new RotatedSecretResponse(rotated.secret().value(), rotated.previousSecretExpiresAt());
        }
    }
}
