package com.eventcore.platform.roles;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class RolePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(RolePropertiesConfiguration.class);

    @Test
    void bindsCommaSeparatedRolesIgnoringCaseAndSpacing() {
        contextRunner.withPropertyValues("eventcore.roles= api , WORKER")
                .run(context -> assertThat(context.getBean(RoleProperties.class).roles())
                        .containsExactlyInAnyOrder(Role.API, Role.WORKER));
    }

    @Test
    void bindsAllRoles() {
        contextRunner.withPropertyValues("eventcore.roles=api,relay,worker")
                .run(context -> assertThat(context.getBean(RoleProperties.class).roles())
                        .containsExactlyInAnyOrder(Role.API, Role.RELAY, Role.WORKER));
    }

    @Test
    void exposesAnImmutableSet() {
        contextRunner.withPropertyValues("eventcore.roles=relay")
                .run(context -> assertThat(context.getBean(RoleProperties.class).roles()).isUnmodifiable());
    }

    @Test
    void failsWhenRolesAreMissing() {
        contextRunner.run(context -> assertThat(context).getFailure()
                .isInstanceOf(ConfigurationPropertiesBindException.class)
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("Field error in object 'eventcore' on field 'roles'"));
    }

    @Test
    void failsWhenRolesAreEmpty() {
        contextRunner.withPropertyValues("eventcore.roles=")
                .run(context -> assertThat(context).getFailure()
                        .isInstanceOf(ConfigurationPropertiesBindException.class)
                        .rootCause()
                        .isInstanceOf(BindValidationException.class)
                        .hasMessageContaining("Field error in object 'eventcore' on field 'roles'"));
    }

    @Test
    void failsWhenARoleIsUnknown() {
        contextRunner.withPropertyValues("eventcore.roles=api,scheduler")
                .run(context -> assertThat(context).getFailure()
                        .isInstanceOf(ConfigurationPropertiesBindException.class)
                        .cause()
                        .isInstanceOf(BindException.class)
                        .hasMessageContaining("eventcore.roles")
                        .rootCause()
                        .hasMessageContaining("scheduler"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RoleProperties.class)
    static class RolePropertiesConfiguration {
    }
}
