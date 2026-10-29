package com.eventcore.delivery.application;

import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnRole(Role.WORKER)
class DeliveryMetrics implements MeterBinder {

    private final DeliveryStore store;

    DeliveryMetrics(DeliveryStore store) {
        this.store = store;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("eventcore.deliveries.pending", store, DeliveryStore::countPending)
                .description("Webhook deliveries that have not reached a final status")
                .register(registry);
        Gauge.builder("eventcore.deliveries.due", store, DeliveryStore::countDue)
                .description("Pending webhook deliveries whose next attempt is due")
                .register(registry);
    }
}
