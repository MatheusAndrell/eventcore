package com.eventcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventcore.support.Containers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class DatabaseMigrationIT {

    @Container
    static final PostgreSQLContainer postgres = Containers.postgres();

    @Test
    void migratesAnEmptyDatabaseAndValidates() throws SQLException {
        Flyway flyway = flyway();

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(flyway.info().applied()).allSatisfy(migration ->
                assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(flyway.info().pending()).isEmpty();
        flyway.validate();
        assertThat(tables()).contains("client", "event", "idempotency_record", "outbox_message", "api_key",
                "subscription", "subscription_secret", "delivery", "delivery_attempt");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void enforcesIntegrityConstraints() throws SQLException {
        flyway().migrate();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("insert into client (id, name, status, created_at) "
                    + "values ('0192f3c8-0000-7000-8000-000000000009', 'constraints', 'ACTIVE', now())");
            assertThatThrownBy(() -> statement.execute("insert into client (id, name, status, created_at) "
                    + "values (gen_random_uuid(), 'invalid', 'UNKNOWN', now())"))
                    .hasMessageContaining("client_status_valid");
            assertThatThrownBy(() -> statement.execute("""
                    insert into idempotency_record (
                        client_id, idempotency_key, request_fingerprint, event_id, created_at, expires_at)
                    values ('0192f3c8-0000-7000-8000-000000000009', 'bad key', decode(repeat('00', 32), 'hex'),
                            gen_random_uuid(), now(), now() + interval '1 day')
                    """)).hasMessageContaining("idempotency_record_key_format");
            assertThatThrownBy(() -> statement.execute("""
                    insert into event (
                        id, client_id, type, data_version, source, occurred_at, received_at, data, status,
                        correlation_id)
                    values (gen_random_uuid(), '0192f3c8-0000-7000-8000-000000000009', 'order.placed', 1, 'urn:a',
                            now(), now(), '[]', 'RECEIVED', 'corr-1')
                    """)).hasMessageContaining("event_data_object");
            assertThatThrownBy(() -> statement.execute("""
                    insert into api_key (
                        id, client_id, key_id, secret_hash, pepper_id, scopes, status, created_at)
                    values (gen_random_uuid(), '0192f3c8-0000-7000-8000-000000000009', 'abcdefghijklmnop',
                            decode(repeat('00', 32), 'hex'), 'p1', array['everything'], 'ACTIVE', now())
                    """)).hasMessageContaining("api_key_scopes_valid");
            statement.execute("""
                    insert into subscription (
                        id, client_id, endpoint_url, event_types, status, created_at, updated_at)
                    values ('0192f3c8-0000-7000-8000-000000000101', '0192f3c8-0000-7000-8000-000000000009',
                            'https://hooks.acme.example/in', array['order.placed'], 'ACTIVE', now(), now())
                    """);
            assertThatThrownBy(() -> statement.execute("""
                    insert into subscription (
                        id, client_id, endpoint_url, event_types, status, created_at, updated_at)
                    values (gen_random_uuid(), '0192f3c8-0000-7000-8000-000000000009',
                            'http://hooks.acme.example/in', array['order.placed'], 'ACTIVE', now(), now())
                    """)).hasMessageContaining("subscription_endpoint_url_https");
            assertThatThrownBy(() -> statement.execute("""
                    insert into subscription (
                        id, client_id, endpoint_url, event_types, status, created_at, updated_at)
                    values (gen_random_uuid(), '0192f3c8-0000-7000-8000-000000000009',
                            'https://hooks.acme.example/in', array['order.placed'], 'DELETED', now(), now())
                    """)).hasMessageContaining("subscription_deletion_consistent");
            statement.execute("""
                    insert into subscription_secret (id, subscription_id, key_id, nonce, ciphertext, created_at)
                    values (gen_random_uuid(), '0192f3c8-0000-7000-8000-000000000101', 'k1',
                            decode(repeat('00', 12), 'hex'), decode(repeat('00', 48), 'hex'), now())
                    """);
            assertThatThrownBy(() -> statement.execute("""
                    insert into subscription_secret (id, subscription_id, key_id, nonce, ciphertext, created_at)
                    values (gen_random_uuid(), '0192f3c8-0000-7000-8000-000000000101', 'k1',
                            decode(repeat('00', 12), 'hex'), decode(repeat('00', 48), 'hex'), now())
                    """)).hasMessageContaining("subscription_secret_current_idx");
            assertThatThrownBy(() -> statement.execute("""
                    insert into subscription_secret (
                        id, subscription_id, key_id, nonce, ciphertext, created_at, expires_at)
                    values (gen_random_uuid(), '0192f3c8-0000-7000-8000-000000000101', 'k1',
                            decode(repeat('00', 8), 'hex'), decode(repeat('00', 48), 'hex'), now(), now())
                    """)).hasMessageContaining("subscription_secret_nonce_length");
        }
    }

    @Test
    void defersTheIdempotencyEventReferenceUntilCommit() throws SQLException {
        flyway().migrate();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("insert into client (id, name, status, created_at) "
                    + "values ('0192f3c8-0000-7000-8000-00000000000a', 'deferred', 'ACTIVE', now())");
            connection.setAutoCommit(false);
            statement.execute("""
                    insert into idempotency_record (
                        client_id, idempotency_key, request_fingerprint, event_id, created_at, expires_at)
                    values ('0192f3c8-0000-7000-8000-00000000000a', 'orphan', decode(repeat('00', 32), 'hex'),
                            gen_random_uuid(), now(), now() + interval '1 day')
                    """);
            assertThatThrownBy(connection::commit).hasMessageContaining("idempotency_record_event_fk");
        }
    }

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static List<String> tables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection connection = connection();
                ResultSet result = connection.getMetaData().getTables(null, "public", "%", new String[] {"TABLE"})) {
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME"));
            }
        }
        return tables;
    }
}
