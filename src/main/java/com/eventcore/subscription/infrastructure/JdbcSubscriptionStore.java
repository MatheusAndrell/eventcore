package com.eventcore.subscription.infrastructure;

import com.eventcore.platform.identity.ClientId;
import com.eventcore.subscription.application.EncryptedSecret;
import com.eventcore.subscription.application.StoredSecret;
import com.eventcore.subscription.application.SubscriptionSecret;
import com.eventcore.subscription.application.SubscriptionStore;
import com.eventcore.subscription.domain.SubscribedEventTypes;
import com.eventcore.subscription.domain.Subscription;
import com.eventcore.subscription.domain.SubscriptionStatus;
import com.eventcore.subscription.domain.WebhookEndpoint;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSubscriptionStore implements SubscriptionStore {

    private static final String SUBSCRIPTION_COLUMNS =
            "id, client_id, endpoint_url, event_types, status, created_at, updated_at";
    private static final String DELETED = "DELETED";

    private final JdbcClient jdbc;

    JdbcSubscriptionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockClient(ClientId clientId) {
        jdbc.sql("select id from client where id = :clientId for no key update")
                .param("clientId", clientId.value())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("client " + clientId + " does not exist"));
    }

    @Override
    public int countSubscriptions(ClientId clientId) {
        return jdbc.sql("select count(*) from subscription where client_id = :clientId and status <> 'DELETED'")
                .param("clientId", clientId.value())
                .query(Integer.class)
                .single();
    }

    @Override
    public void insert(Subscription subscription) {
        jdbc.sql("""
                        insert into subscription (
                            id, client_id, endpoint_url, event_types, status, created_at, updated_at)
                        values (:id, :clientId, :endpointUrl, :eventTypes, :status, :createdAt, :updatedAt)
                        """)
                .param("id", subscription.id())
                .param("clientId", subscription.clientId().value())
                .param("endpointUrl", subscription.endpoint().toString())
                .param("eventTypes", eventTypes(subscription))
                .param("status", subscription.status().name())
                .param("createdAt", utc(subscription.createdAt()))
                .param("updatedAt", utc(subscription.updatedAt()))
                .update();
    }

    @Override
    public void update(Subscription subscription) {
        int updated = jdbc.sql("""
                        update subscription
                           set endpoint_url = :endpointUrl, event_types = :eventTypes, status = :status,
                               updated_at = :updatedAt
                         where id = :id and client_id = :clientId and status <> 'DELETED'
                        """)
                .param("endpointUrl", subscription.endpoint().toString())
                .param("eventTypes", eventTypes(subscription))
                .param("status", subscription.status().name())
                .param("updatedAt", utc(subscription.updatedAt()))
                .param("id", subscription.id())
                .param("clientId", subscription.clientId().value())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("subscription " + subscription.id() + " is no longer available");
        }
    }

    @Override
    public List<Subscription> list(ClientId clientId) {
        return jdbc.sql("select " + SUBSCRIPTION_COLUMNS + """
                         from subscription
                        where client_id = :clientId and status <> 'DELETED'
                        order by id
                        """)
                .param("clientId", clientId.value())
                .query(JdbcSubscriptionStore::subscription)
                .list();
    }

    @Override
    public Optional<Subscription> find(ClientId clientId, UUID subscriptionId) {
        return findSubscription(clientId, subscriptionId, "");
    }

    @Override
    public Optional<Subscription> findForUpdate(ClientId clientId, UUID subscriptionId) {
        return findSubscription(clientId, subscriptionId, " for update");
    }

    @Override
    public boolean markDeleted(ClientId clientId, UUID subscriptionId, Instant deletedAt) {
        return jdbc.sql("""
                        update subscription set status = :deleted, deleted_at = :deletedAt
                         where id = :id and client_id = :clientId and status <> :deleted
                        """)
                .param("deleted", DELETED)
                .param("deletedAt", utc(deletedAt))
                .param("id", subscriptionId)
                .param("clientId", clientId.value())
                .update() == 1;
    }

    @Override
    public void insertSecret(SubscriptionSecret secret) {
        jdbc.sql("""
                        insert into subscription_secret (id, subscription_id, key_id, nonce, ciphertext, created_at)
                        values (:id, :subscriptionId, :keyId, :nonce, :ciphertext, :createdAt)
                        """)
                .param("id", secret.id())
                .param("subscriptionId", secret.subscriptionId())
                .param("keyId", secret.secret().keyId())
                .param("nonce", secret.secret().nonce())
                .param("ciphertext", secret.secret().ciphertext())
                .param("createdAt", utc(secret.createdAt()))
                .update();
    }

    @Override
    public void expireCurrentSecret(UUID subscriptionId, Instant expiresAt) {
        jdbc.sql("""
                        update subscription_secret set expires_at = :expiresAt
                         where subscription_id = :subscriptionId and expires_at is null
                        """)
                .param("expiresAt", utc(expiresAt))
                .param("subscriptionId", subscriptionId)
                .update();
    }

    @Override
    public void deleteSecretsExpiredBy(UUID subscriptionId, Instant now) {
        jdbc.sql("delete from subscription_secret where subscription_id = :subscriptionId and expires_at <= :now")
                .param("subscriptionId", subscriptionId)
                .param("now", utc(now))
                .update();
    }

    @Override
    public void deleteSecrets(UUID subscriptionId) {
        jdbc.sql("delete from subscription_secret where subscription_id = :subscriptionId")
                .param("subscriptionId", subscriptionId)
                .update();
    }

    @Override
    public List<UUID> activeSubscriptionIds(ClientId clientId, String eventType) {
        return jdbc.sql("""
                        select id from subscription
                         where client_id = :clientId and status = 'ACTIVE' and :eventType = any (event_types)
                         order by id
                        """)
                .param("clientId", clientId.value())
                .param("eventType", eventType)
                .query(UUID.class)
                .list();
    }

    @Override
    public Optional<Subscription> findUndeleted(UUID subscriptionId) {
        return jdbc.sql("select " + SUBSCRIPTION_COLUMNS + " from subscription where id = :id and status <> 'DELETED'")
                .param("id", subscriptionId)
                .query(JdbcSubscriptionStore::subscription)
                .optional();
    }

    @Override
    public List<StoredSecret> validSecrets(UUID subscriptionId, Instant now) {
        return jdbc.sql("""
                        select id, key_id, nonce, ciphertext from subscription_secret
                         where subscription_id = :subscriptionId and (expires_at is null or expires_at > :now)
                         order by created_at desc, id
                        """)
                .param("subscriptionId", subscriptionId)
                .param("now", utc(now))
                .query((row, rowNumber) -> new StoredSecret(row.getObject("id", UUID.class),
                        new EncryptedSecret(row.getString("key_id"), row.getBytes("nonce"), row.getBytes("ciphertext"))))
                .list();
    }

    private Optional<Subscription> findSubscription(ClientId clientId, UUID subscriptionId, String lockClause) {
        return jdbc.sql("select " + SUBSCRIPTION_COLUMNS + """
                         from subscription
                        where id = :id and client_id = :clientId and status <> 'DELETED'
                        """ + lockClause)
                .param("id", subscriptionId)
                .param("clientId", clientId.value())
                .query(JdbcSubscriptionStore::subscription)
                .optional();
    }

    private static String[] eventTypes(Subscription subscription) {
        return subscription.eventTypes().values().toArray(String[]::new);
    }

    private static Subscription subscription(ResultSet row, int rowNumber) throws SQLException {
        return new Subscription(
                row.getObject("id", UUID.class),
                new ClientId(row.getObject("client_id", UUID.class)),
                new WebhookEndpoint(URI.create(row.getString("endpoint_url"))),
                SubscribedEventTypes.of(List.of((String[]) row.getArray("event_types").getArray())),
                SubscriptionStatus.valueOf(row.getString("status")),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
