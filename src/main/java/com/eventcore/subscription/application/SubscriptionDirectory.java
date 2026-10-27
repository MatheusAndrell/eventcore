package com.eventcore.subscription.application;

import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import com.eventcore.subscription.domain.SubscriptionStatus;
import com.eventcore.subscription.domain.WebhookSecret;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnRole(Role.WORKER)
public class SubscriptionDirectory {

    private final SubscriptionStore store;
    private final WebhookSecretCipher cipher;
    private final Clock clock;

    public SubscriptionDirectory(SubscriptionStore store, WebhookSecretCipher cipher, Clock clock) {
        this.store = store;
        this.cipher = cipher;
        this.clock = clock;
    }

    public List<UUID> activeSubscriptions(ClientId clientId, String eventType) {
        return store.activeSubscriptionIds(clientId, eventType);
    }

    public Optional<DeliveryTarget> deliveryTarget(UUID subscriptionId) {
        return store.findUndeleted(subscriptionId).map(subscription -> {
            if (subscription.status() != SubscriptionStatus.ACTIVE) {
                return new DeliveryTarget(subscriptionId, subscription.endpoint().uri(), false, List.of());
            }
            List<byte[]> keys = store.validSecrets(subscriptionId, clock.instant()).stream()
                    .map(stored -> cipher.decrypt(stored.secret(), stored.id()))
                    .map(WebhookSecret::key)
                    .toList();
            if (keys.isEmpty()) {
                throw new IllegalStateException("active subscription " + subscriptionId + " has no valid secret");
            }
            return new DeliveryTarget(subscriptionId, subscription.endpoint().uri(), true, keys);
        });
    }
}
