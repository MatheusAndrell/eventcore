package com.eventcore.platform.messaging;

import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.WORKER)
class ListenerRetryConfiguration {

    @Bean
    RabbitListenerRetrySettingsCustomizer rejectedMessagesAreNotRetried() {
        return settings -> settings.setExceptionPredicate(ListenerRetryConfiguration::retriable);
    }

    static boolean retriable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AmqpRejectAndDontRequeueException) {
                return false;
            }
        }
        return true;
    }
}
