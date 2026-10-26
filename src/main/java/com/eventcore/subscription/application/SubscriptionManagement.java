package com.eventcore.subscription.application;

import com.eventcore.catalog.application.EventCatalog;
import com.eventcore.platform.egress.EgressPolicy;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.identity.UuidV7Generator;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import com.eventcore.subscription.domain.SubscribedEventTypes;
import com.eventcore.subscription.domain.Subscription;
import com.eventcore.subscription.domain.SubscriptionChanges;
import com.eventcore.subscription.domain.WebhookEndpoint;
import com.eventcore.subscription.domain.WebhookSecret;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
@ConditionalOnRole(Role.API)
public class SubscriptionManagement {

    private final SubscriptionStore store;
    private final WebhookSecretCipher cipher;
    private final EventCatalog catalog;
    private final EgressPolicy egressPolicy;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final SubscriptionProperties properties;
    private final SecureRandom random = new SecureRandom();

    public SubscriptionManagement(
            SubscriptionStore store,
            WebhookSecretCipher cipher,
            EventCatalog catalog,
            EgressPolicy egressPolicy,
            UuidV7Generator ids,
            Clock clock,
            TransactionOperations transactions,
            SubscriptionProperties properties) {
        this.store = store;
        this.cipher = cipher;
        this.catalog = catalog;
        this.egressPolicy = egressPolicy;
        this.ids = ids;
        this.clock = clock;
        this.transactions = transactions;
        this.properties = properties;
    }

    public CreatedSubscription create(ClientId clientId, WebhookEndpoint endpoint, SubscribedEventTypes eventTypes) {
        requireCatalogTypes(eventTypes);
        egressPolicy.check(endpoint.uri());
        Instant now = now();
        Subscription subscription = Subscription.create(ids.next(), clientId, endpoint, eventTypes, now);
        WebhookSecret secret = WebhookSecret.generate(random);
        transactions.executeWithoutResult(status -> {
            store.lockClient(clientId);
            if (store.countSubscriptions(clientId) >= properties.maxPerClient()) {
                throw new SubscriptionLimitReachedException(properties.maxPerClient());
            }
            store.insert(subscription);
            store.insertSecret(encrypt(subscription.id(), secret, now));
        });
        return new CreatedSubscription(subscription, secret);
    }

    public List<Subscription> list(ClientId clientId) {
        return store.list(clientId);
    }

    public Optional<Subscription> find(ClientId clientId, UUID subscriptionId) {
        return store.find(clientId, subscriptionId);
    }

    public Optional<Subscription> change(ClientId clientId, UUID subscriptionId, SubscriptionChanges changes) {
        changes.eventTypes().ifPresent(this::requireCatalogTypes);
        changes.endpoint().ifPresent(endpoint -> egressPolicy.check(endpoint.uri()));
        return transactions.execute(status -> store.findForUpdate(clientId, subscriptionId).map(current -> {
            Subscription changed = current.apply(changes, now());
            store.update(changed);
            return changed;
        }));
    }

    public boolean delete(ClientId clientId, UUID subscriptionId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!store.markDeleted(clientId, subscriptionId, now())) {
                return false;
            }
            store.deleteSecrets(subscriptionId);
            return true;
        }));
    }

    public Optional<RotatedSecret> rotateSecret(ClientId clientId, UUID subscriptionId) {
        WebhookSecret secret = WebhookSecret.generate(random);
        return transactions.execute(status -> store.findForUpdate(clientId, subscriptionId).map(subscription -> {
            Instant now = now();
            Instant previousSecretExpiresAt = now.plus(properties.secretRotationGracePeriod());
            store.deleteSecretsExpiredBy(subscriptionId, now);
            store.expireCurrentSecret(subscriptionId, previousSecretExpiresAt);
            store.insertSecret(encrypt(subscriptionId, secret, now));
            return new RotatedSecret(secret, previousSecretExpiresAt);
        }));
    }

    private void requireCatalogTypes(SubscribedEventTypes eventTypes) {
        List<String> unknown = eventTypes.values().stream().filter(type -> !catalog.contains(type)).toList();
        if (!unknown.isEmpty()) {
            throw new UnknownEventTypesException(unknown);
        }
    }

    private SubscriptionSecret encrypt(UUID subscriptionId, WebhookSecret secret, Instant now) {
        UUID secretId = ids.next();
        return new SubscriptionSecret(secretId, subscriptionId, cipher.encrypt(secret, secretId), now);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
