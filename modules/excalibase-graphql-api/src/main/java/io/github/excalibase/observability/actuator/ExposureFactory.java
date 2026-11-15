package io.github.excalibase.observability.actuator;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;

@Factory
class ExposureFactory {

    @Singleton
    EndpointExposure exposure(Environment environment) {
        return ExposedEndpointCondition.exposure(environment);
    }
}
