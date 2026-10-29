package com.eventcore.operations.application;

import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnRole(Role.WORKER)
class ParkingMetrics implements MeterBinder {

    private final ParkedMessages parkedMessages;

    ParkingMetrics(ParkedMessages parkedMessages) {
        this.parkedMessages = parkedMessages;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("eventcore.parking.messages", parkedMessages, ParkedMessages::count)
                .description("Messages parked after they could not be processed")
                .register(registry);
    }
}
