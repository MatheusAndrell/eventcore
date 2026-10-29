package com.eventcore.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DataAccessResourceFailureException;

class ListenerRetryConfigurationTest {

    @Test
    void retriesInfrastructureFailures() {
        assertThat(ListenerRetryConfiguration.retriable(new IllegalStateException("listener failed",
                new DataAccessResourceFailureException("database unavailable")))).isTrue();
    }

    @Test
    void neverRetriesMessagesRejectedOnPurpose() {
        assertThat(ListenerRetryConfiguration.retriable(new AmqpRejectAndDontRequeueException("unprocessable")))
                .isFalse();
        assertThat(ListenerRetryConfiguration.retriable(new IllegalStateException("listener failed",
                new AmqpRejectAndDontRequeueException("unprocessable")))).isFalse();
    }
}
