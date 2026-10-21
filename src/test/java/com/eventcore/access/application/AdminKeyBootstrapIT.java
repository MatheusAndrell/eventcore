package com.eventcore.access.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventcore.access.domain.ApiKeyToken;
import com.eventcore.access.domain.Scope;
import com.eventcore.support.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"eventcore.roles=api", "eventcore.security.bootstrap-admin-key=" + AdminKeyBootstrapIT.BOOTSTRAP_KEY})
@Import(TestcontainersConfiguration.class)
class AdminKeyBootstrapIT {

    static final String BOOTSTRAP_KEY = "ec_bootstrapadminky_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    @Autowired
    private ApiKeyAuthenticator authenticator;

    @Autowired
    private ClientAdministration administration;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void registersTheBootstrapKeyAtStartupOnlyOnce() {
        assertThat(authenticator.authenticate(BOOTSTRAP_KEY)).hasValueSatisfying(
                client -> assertThat(client.scopes()).containsExactly(Scope.ADMIN));
        assertThat(administration.bootstrapAdminKey(ApiKeyToken.parse(BOOTSTRAP_KEY).orElseThrow())).isFalse();
        assertThat(jdbc.sql("select count(*) from api_key where 'admin' = any (scopes)").query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select c.name from client c join api_key k on k.client_id = c.id")
                .query(String.class).single()).isEqualTo("eventcore-admin");
    }

    @Test
    void refusesAMalformedBootstrapKeyWithoutEchoingIt() {
        ApiKeyProperties properties = new ApiKeyProperties(List.of("unused"), "unused", "malformed-bootstrap-value");
        AdminKeyBootstrap bootstrap = new AdminKeyBootstrap(properties, administration);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a valid EventCore API key")
                .message().doesNotContain("malformed-bootstrap-value");
    }
}
