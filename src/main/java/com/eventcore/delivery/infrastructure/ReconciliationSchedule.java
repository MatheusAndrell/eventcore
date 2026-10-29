package com.eventcore.delivery.infrastructure;

import com.eventcore.delivery.application.Reconciliation;
import com.eventcore.delivery.application.ReconcilerProperties;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnRole(Role.WORKER)
class ReconciliationSchedule implements SchedulingConfigurer {

    private final Reconciliation reconciliation;
    private final ReconcilerProperties properties;

    ReconciliationSchedule(Reconciliation reconciliation, ReconcilerProperties properties) {
        this.reconciliation = reconciliation;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(
                new FixedDelayTask(reconciliation::reconcile, properties.interval(), properties.interval()));
    }
}
