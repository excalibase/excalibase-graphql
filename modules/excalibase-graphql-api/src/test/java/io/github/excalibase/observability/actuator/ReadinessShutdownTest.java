package io.github.excalibase.observability.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReadinessShutdownTest {

    @Test
    @DisplayName("readiness is accepting traffic until the application starts shutting down")
    void refusesTrafficOnceStopping() {
        ReadinessState readiness = new ReadinessState();
        assertThat(readiness.status()).isEqualTo(HealthStatus.UP);

        readiness.refuseTraffic();

        assertThat(readiness.status()).isEqualTo(HealthStatus.OUT_OF_SERVICE);
    }
}
