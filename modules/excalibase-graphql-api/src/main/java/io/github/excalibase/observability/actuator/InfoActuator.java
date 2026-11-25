package io.github.excalibase.observability.actuator;

import io.github.excalibase.metadata.MetadataHealth;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Read;
import java.util.Map;

/**
 * {@code /actuator/info}: empty, unless a metadata source is configured, in which case it reports this
 * replica's metadata version, source and last refusal, so a replica stuck on an old version is visible.
 */
@Endpoint(id = "info", prefix = ActuatorEndpoint.PREFIX, defaultEnabled = true, defaultSensitive = false)
@Requires(condition = ExposedEndpointCondition.class)
public class InfoActuator {

    private final MetadataHealth metadata;

    public InfoActuator(@Nullable MetadataHealth metadata) {
        this.metadata = metadata;
    }

    @Read(produces = ActuatorEndpoint.JSON)
    public Map<String, Object> info() {
        return metadata == null ? Map.of() : metadata.report().<Map<String, Object>>map(report -> Map.of("metadata", report))
                .orElse(Map.of());
    }
}
