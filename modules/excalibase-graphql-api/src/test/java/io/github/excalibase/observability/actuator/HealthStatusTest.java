package io.github.excalibase.observability.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HealthStatusTest {

    @Test
    @DisplayName("UP answers 200; DOWN and OUT_OF_SERVICE answer 503")
    void httpStatus() {
        assertThat(HealthStatus.UP.httpStatus()).isEqualTo(200);
        assertThat(HealthStatus.DOWN.httpStatus()).isEqualTo(503);
        assertThat(HealthStatus.OUT_OF_SERVICE.httpStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("the worst status wins when several indicators are combined")
    void worstWins() {
        assertThat(HealthStatus.combine(HealthStatus.UP, HealthStatus.UP)).isEqualTo(HealthStatus.UP);
        assertThat(HealthStatus.combine(HealthStatus.UP, HealthStatus.DOWN)).isEqualTo(HealthStatus.DOWN);
        assertThat(HealthStatus.combine(HealthStatus.OUT_OF_SERVICE, HealthStatus.UP)).isEqualTo(HealthStatus.OUT_OF_SERVICE);
        assertThat(HealthStatus.combine()).isEqualTo(HealthStatus.UP);
    }
}
