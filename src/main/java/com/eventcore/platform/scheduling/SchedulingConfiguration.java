package com.eventcore.platform.scheduling;

import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.WORKER)
@EnableScheduling
class SchedulingConfiguration {
}
