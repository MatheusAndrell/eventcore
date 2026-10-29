package com.eventcore.outbox.application;

import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnRole(Role.RELAY)
class OutboxMetrics implements MeterBinder {

    private final OutboxStore store;

    OutboxMetrics(OutboxStore store) {
        this.store = store;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("eventcore.outbox.pending", store, OutboxStore::countPending)
                .description("Outbox messages waiting to be published")
                .register(registry);
        Gauge.builder("eventcore.outbox.oldest.pending.age", store,
                        outbox -> outbox.oldestPendingAge().map(Duration::toMillis).orElse(0L) / 1000.0)
                .description("Age of the oldest outbox message waiting to be published")
                .baseUnit("seconds")
                .register(registry);
    }
}
