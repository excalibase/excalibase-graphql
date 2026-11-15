package io.github.excalibase.observability.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micronaut.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What health says while the application drains: the same as the earlier build, with and without the probes. */
class HealthDrainTest {

    private final DatabaseHealth database = mock(DatabaseHealth.class);
    private final ReadinessState readiness = new ReadinessState();

    private HealthActuator actuator(boolean probes) {
        when(database.status()).thenReturn(HealthStatus.UP);
        return new HealthActuator(database, readiness, probes);
    }

    @Test
    @DisplayName("with probes on, draining makes health OUT_OF_SERVICE (503), readiness 503, liveness still UP")
    void drainingWithProbes() {
        HealthActuator actuator = actuator(true);
        readiness.refuseTraffic();

        HttpResponse<Map<String, Object>> health = actuator.health();
        assertThat(health.code()).isEqualTo(503);
        assertThat(health.body()).containsEntry("status", "OUT_OF_SERVICE");
        assertThat(health.body()).containsKey("groups");
        assertThat(actuator.group("readiness").code()).isEqualTo(503);
        assertThat(actuator.group("readiness").body()).isEqualTo(Map.of("status", "OUT_OF_SERVICE"));
        assertThat(actuator.group("liveness").code()).isEqualTo(200);
    }

    @Test
    @DisplayName("without probes, draining leaves health UP (200) and the groups absent")
    void drainingWithoutProbes() {
        HealthActuator actuator = actuator(false);
        readiness.refuseTraffic();

        assertThat(actuator.health().code()).isEqualTo(200);
        assertThat(actuator.health().body()).isEqualTo(Map.of("status", "UP"));
        assertThat(actuator.group("readiness").code()).isEqualTo(404);
    }

    @Test
    @DisplayName("a database that is down outranks a draining readiness")
    void downOutranksDraining() {
        HealthActuator actuator = actuator(true);
        when(database.status()).thenReturn(HealthStatus.DOWN);
        readiness.refuseTraffic();

        assertThat(actuator.health().body()).containsEntry("status", "DOWN");
    }
}
