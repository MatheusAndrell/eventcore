package com.eventcore.platform.roles;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

class RoleCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnRole.class.getName());
        if (attributes == null) {
            return false;
        }
        Set<String> configured = ConfiguredRoles.of(context.getEnvironment());
        return Arrays.stream((Role[]) attributes.get("value")).anyMatch(role -> ConfiguredRoles.includes(configured, role));
    }
}
