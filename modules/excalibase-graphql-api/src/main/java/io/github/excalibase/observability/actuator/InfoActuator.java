package io.github.excalibase.observability.actuator;

import io.micronaut.context.annotation.Requires;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Read;
import java.util.Map;

/** {@code /actuator/info}: nothing to report, an empty object. */
@Endpoint(id = "info", prefix = ActuatorEndpoint.PREFIX, defaultEnabled = true, defaultSensitive = false)
@Requires(condition = ExposedEndpointCondition.class)
public class InfoActuator {

    @Read(produces = ActuatorEndpoint.JSON)
    public Map<String, Object> info() {
        return Map.of();
    }
}
