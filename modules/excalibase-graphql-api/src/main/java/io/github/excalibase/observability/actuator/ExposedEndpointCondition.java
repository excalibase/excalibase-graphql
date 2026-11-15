package io.github.excalibase.observability.actuator;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.core.value.PropertyResolver;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.management.endpoint.annotation.Endpoint;

/** Keeps an endpoint bean, and so its route, only when the exposure settings name it. */
public final class ExposedEndpointCondition implements Condition {

    public static final String INCLUDE = "management.endpoints.web.exposure.include";
    public static final String EXCLUDE = "management.endpoints.web.exposure.exclude";

    @Override
    public boolean matches(ConditionContext context) {
        if (!(context.getComponent() instanceof BeanDefinition<?> definition)) {
            return true;
        }
        String id = definition.getAnnotationMetadata().stringValue(Endpoint.class, "id").orElse("");
        return exposure(context).exposes(id);
    }

    static EndpointExposure exposure(PropertyResolver properties) {
        return EndpointExposure.of(properties.getProperty(INCLUDE, String.class).orElse(""),
                properties.getProperty(EXCLUDE, String.class).orElse(""));
    }
}
