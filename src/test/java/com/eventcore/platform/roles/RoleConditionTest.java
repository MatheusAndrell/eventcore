package com.eventcore.platform.roles;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class RoleConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RoleScopedBeans.class);

    @Test
    void createsOnlyTheBeansOfTheConfiguredRoles() {
        contextRunner.withPropertyValues("eventcore.roles=api, WORKER").run(context -> {
            assertThat(context).hasBean("apiComponent");
            assertThat(context).hasBean("workerComponent");
            assertThat(context).doesNotHaveBean("relayComponent");
        });
    }

    @Test
    void createsBeansThatAcceptAnyOfSeveralRoles() {
        contextRunner.withPropertyValues("eventcore.roles=relay").run(context -> {
            assertThat(context).hasBean("brokerComponent");
            assertThat(context).doesNotHaveBean("apiComponent");
        });
        contextRunner.withPropertyValues("eventcore.roles=api")
                .run(context -> assertThat(context).doesNotHaveBean("brokerComponent"));
    }

    @Test
    void createsNoRoleScopedBeansWithoutRoles() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("apiComponent");
            assertThat(context).doesNotHaveBean("relayComponent");
            assertThat(context).doesNotHaveBean("workerComponent");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RoleScopedBeans {

        @Bean
        @ConditionalOnRole(Role.API)
        String apiComponent() {
            return "api";
        }

        @Bean
        @ConditionalOnRole(Role.RELAY)
        String relayComponent() {
            return "relay";
        }

        @Bean
        @ConditionalOnRole(Role.WORKER)
        String workerComponent() {
            return "worker";
        }

        @Bean
        @ConditionalOnRole({Role.RELAY, Role.WORKER})
        String brokerComponent() {
            return "broker";
        }
    }
}
