package com.eventcore.access.application;

import com.eventcore.access.domain.ApiKeyToken;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnRole(Role.API)
class AdminKeyBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminKeyBootstrap.class);

    private final ApiKeyProperties properties;
    private final ClientAdministration administration;

    AdminKeyBootstrap(ApiKeyProperties properties, ClientAdministration administration) {
        this.properties = properties;
        this.administration = administration;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        properties.configuredBootstrapAdminKey().ifPresent(this::bootstrap);
    }

    private void bootstrap(String configured) {
        ApiKeyToken token = ApiKeyToken.parse(configured).orElseThrow(() -> new IllegalStateException(
                "eventcore.security.bootstrap-admin-key is not a valid EventCore API key"));
        if (administration.bootstrapAdminKey(token)) {
            log.warn("bootstrap admin API key {} registered; rotate it and remove the bootstrap setting", token.keyId());
        } else {
            log.info("bootstrap admin API key ignored because a usable admin key or the same key id already exists");
        }
    }
}
