package io.github.excalibase.observability.actuator;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Read;
import io.micronaut.management.endpoint.annotation.Selector;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /actuator/health}: UP, or DOWN with 503 when the configured database does not answer; with probes on, the
 * liveness and readiness groups too, and readiness takes part in the overall status (OUT_OF_SERVICE while draining). The body carries the status only, never details.
 */
@Endpoint(id = "health", prefix = ActuatorEndpoint.PREFIX, defaultEnabled = true, defaultSensitive = false)
@Requires(condition = ExposedEndpointCondition.class)
public class HealthActuator {

    private static final String LIVENESS = "liveness";
    private static final String READINESS = "readiness";

    private final DatabaseHealth database;
    private final ReadinessState readiness;
    private final boolean probes;

    HealthActuator(DatabaseHealth database, ReadinessState readiness,
                   @Value("${management.endpoint.health.probes.enabled:false}") boolean probes) {
        this.database = database;
        this.readiness = readiness;
        this.probes = probes;
    }

    @Read(produces = ActuatorEndpoint.JSON)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<Map<String, Object>> health() {
        HealthStatus status = HealthStatus.combine(database.status(), probes ? readiness.status() : HealthStatus.UP);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.name());
        if (probes) {
            body.put("groups", List.of(LIVENESS, READINESS));
        }
        return HttpResponse.<Map<String, Object>>status(HttpStatus.valueOf(status.httpStatus())).body(body);
    }

    @Read(produces = ActuatorEndpoint.JSON)
    public HttpResponse<Map<String, Object>> group(@Selector String group) {
        HealthStatus status = groupStatus(group);
        if (status == null) {
            return HttpResponse.notFound();
        }
        return HttpResponse.<Map<String, Object>>status(HttpStatus.valueOf(status.httpStatus()))
                .body(Map.of("status", status.name()));
    }

    private HealthStatus groupStatus(String group) {
        if (!probes) {
            return null;
        }
        return switch (group) {
            case LIVENESS -> HealthStatus.UP;
            case READINESS -> readiness.status();
            default -> null;
        };
    }
}
