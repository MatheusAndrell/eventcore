package com.eventcore.access.infrastructure;

import com.eventcore.access.application.AccessStore;
import com.eventcore.access.application.StoredCredential;
import com.eventcore.access.domain.ApiKey;
import com.eventcore.access.domain.ApiKeyStatus;
import com.eventcore.access.domain.Client;
import com.eventcore.access.domain.ClientName;
import com.eventcore.access.domain.ClientStatus;
import com.eventcore.access.domain.Scope;
import com.eventcore.platform.identity.ClientId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccessStore implements AccessStore {

    private static final String API_KEY_COLUMNS = """
            k.id, k.client_id, k.key_id, k.scopes, k.status, k.created_at, k.expires_at, k.revoked_at, k.last_used_at
            """;

    private final JdbcClient jdbc;

    JdbcAccessStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertClient(Client client) {
        return jdbc.sql("""
                        insert into client (id, name, status, created_at)
                        values (:id, :name, :status, :createdAt)
                        on conflict (name) do nothing
                        """)
                .param("id", client.id().value())
                .param("name", client.name().value())
                .param("status", client.status().name())
                .param("createdAt", utc(client.createdAt()))
                .update() == 1;
    }

    @Override
    public Optional<Client> findClient(ClientId clientId) {
        return jdbc.sql("select id, name, status, created_at from client where id = :id")
                .param("id", clientId.value())
                .query(JdbcAccessStore::client)
                .optional();
    }

    @Override
    public Optional<Client> findClientByName(ClientName name) {
        return jdbc.sql("select id, name, status, created_at from client where name = :name")
                .param("name", name.value())
                .query(JdbcAccessStore::client)
                .optional();
    }

    @Override
    public boolean updateClientStatus(ClientId clientId, ClientStatus status) {
        return jdbc.sql("update client set status = :status where id = :id")
                .param("status", status.name())
                .param("id", clientId.value())
                .update() == 1;
    }

    @Override
    public boolean insertApiKey(ApiKey apiKey, byte[] secretHash, String pepperId) {
        return jdbc.sql("""
                        insert into api_key (
                            id, client_id, key_id, secret_hash, pepper_id, scopes, status, created_at, expires_at)
                        values (
                            :id, :clientId, :keyId, :secretHash, :pepperId, :scopes, :status, :createdAt, :expiresAt)
                        on conflict (key_id) do nothing
                        """)
                .param("id", apiKey.id())
                .param("clientId", apiKey.clientId().value())
                .param("keyId", apiKey.keyId())
                .param("secretHash", secretHash)
                .param("pepperId", pepperId)
                .param("scopes", apiKey.scopes().stream().map(Scope::value).sorted().toArray(String[]::new))
                .param("status", apiKey.status().name())
                .param("createdAt", utc(apiKey.createdAt()))
                .param("expiresAt", apiKey.expiresAt().map(JdbcAccessStore::utc).orElse(null))
                .update() == 1;
    }

    @Override
    public Optional<StoredCredential> findCredential(String keyId) {
        return jdbc.sql("select " + API_KEY_COLUMNS + """
                        , k.secret_hash, k.pepper_id, c.status as client_status
                          from api_key k
                          join client c on c.id = k.client_id
                         where k.key_id = :keyId
                        """)
                .param("keyId", keyId)
                .query((row, rowNumber) -> new StoredCredential(
                        apiKey(row),
                        row.getBytes("secret_hash"),
                        row.getString("pepper_id"),
                        ClientStatus.valueOf(row.getString("client_status"))))
                .optional();
    }

    @Override
    public List<ApiKey> listApiKeys(ClientId clientId) {
        return jdbc.sql("select " + API_KEY_COLUMNS + " from api_key k where k.client_id = :clientId order by k.id")
                .param("clientId", clientId.value())
                .query((row, rowNumber) -> apiKey(row))
                .list();
    }

    @Override
    public boolean revokeApiKey(ClientId clientId, UUID apiKeyId, Instant revokedAt) {
        return jdbc.sql("""
                        update api_key set status = 'REVOKED', revoked_at = :revokedAt
                         where id = :id and client_id = :clientId and status = 'ACTIVE'
                        """)
                .param("revokedAt", utc(revokedAt))
                .param("id", apiKeyId)
                .param("clientId", clientId.value())
                .update() == 1;
    }

    @Override
    public void recordApiKeyUse(UUID apiKeyId, Instant usedAt) {
        jdbc.sql("update api_key set last_used_at = :usedAt where id = :id")
                .param("usedAt", utc(usedAt))
                .param("id", apiKeyId)
                .update();
    }

    @Override
    public boolean hasUsableAdminKey(Instant now) {
        return jdbc.sql("""
                        select exists (
                            select 1 from api_key k join client c on c.id = k.client_id
                             where k.status = 'ACTIVE' and c.status = 'ACTIVE' and 'admin' = any (k.scopes)
                               and (k.expires_at is null or k.expires_at > :now))
                        """)
                .param("now", utc(now))
                .query(Boolean.class)
                .single();
    }

    private static Client client(ResultSet row, int rowNumber) throws SQLException {
        return new Client(
                new ClientId(row.getObject("id", UUID.class)),
                new ClientName(row.getString("name")),
                ClientStatus.valueOf(row.getString("status")),
                instant(row, "created_at"));
    }

    private static ApiKey apiKey(ResultSet row) throws SQLException {
        return new ApiKey(
                row.getObject("id", UUID.class),
                new ClientId(row.getObject("client_id", UUID.class)),
                row.getString("key_id"),
                scopes(row.getArray("scopes")),
                ApiKeyStatus.valueOf(row.getString("status")),
                instant(row, "created_at"),
                optionalInstant(row, "expires_at"),
                optionalInstant(row, "revoked_at"),
                optionalInstant(row, "last_used_at"));
    }

    private static Set<Scope> scopes(Array array) throws SQLException {
        return Arrays.stream((String[]) array.getArray())
                .map(value -> Scope.fromValue(value).orElseThrow(
                        () -> new IllegalStateException("unknown scope stored in the database: " + value)))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Optional<Instant> optionalInstant(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, OffsetDateTime.class)).map(OffsetDateTime::toInstant);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
