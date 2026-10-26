package com.eventcore.subscription.application;

import com.eventcore.platform.identity.ClientId;
import com.eventcore.subscription.domain.Subscription;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionStore {

    void lockClient(ClientId clientId);

    int countSubscriptions(ClientId clientId);

    void insert(Subscription subscription);

    void update(Subscription subscription);

    List<Subscription> list(ClientId clientId);

    Optional<Subscription> find(ClientId clientId, UUID subscriptionId);

    Optional<Subscription> findForUpdate(ClientId clientId, UUID subscriptionId);

    boolean markDeleted(ClientId clientId, UUID subscriptionId, Instant deletedAt);

    void insertSecret(SubscriptionSecret secret);

    void expireCurrentSecret(UUID subscriptionId, Instant expiresAt);

    void deleteSecretsExpiredBy(UUID subscriptionId, Instant now);

    void deleteSecrets(UUID subscriptionId);
}
