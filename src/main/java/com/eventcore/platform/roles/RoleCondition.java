package com.eventcore.platform.roles;

import java.util.Map;
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
        Role required = (Role) attributes.get("value");
        return ConfiguredRoles.includes(ConfiguredRoles.of(context.getEnvironment()), required);
    }
}
