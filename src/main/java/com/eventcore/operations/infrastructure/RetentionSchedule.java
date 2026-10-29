package com.eventcore.operations.infrastructure;

import com.eventcore.operations.application.Retention;
import com.eventcore.operations.application.RetentionProperties;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnRole(Role.WORKER)
class RetentionSchedule implements SchedulingConfigurer {

    private final Retention retention;
    private final RetentionProperties properties;

    RetentionSchedule(Retention retention, RetentionProperties properties) {
        this.retention = retention;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(retention::purge, properties.interval(), properties.interval()));
    }
}
