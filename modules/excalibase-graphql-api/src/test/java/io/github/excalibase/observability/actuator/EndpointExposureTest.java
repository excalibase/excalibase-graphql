package io.github.excalibase.observability.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EndpointExposureTest {

    @Test
    @DisplayName("an endpoint is exposed when the include list names it")
    void includeList() {
        EndpointExposure exposure = EndpointExposure.of("health,info", "");

        assertThat(exposure.exposes("health")).isTrue();
        assertThat(exposure.exposes("info")).isTrue();
        assertThat(exposure.exposes("metrics")).isFalse();
    }

    @Test
    @DisplayName("names are trimmed and compared without regard to case")
    void looseNames() {
        EndpointExposure exposure = EndpointExposure.of(" Health , PROMETHEUS ", "");

        assertThat(exposure.exposes("health")).isTrue();
        assertThat(exposure.exposes("prometheus")).isTrue();
    }

    @Test
    @DisplayName("a star exposes everything, and the exclude list still wins over it")
    void starAndExclude() {
        EndpointExposure exposure = EndpointExposure.of("*", "metrics");

        assertThat(exposure.exposes("health")).isTrue();
        assertThat(exposure.exposes("metrics")).isFalse();
    }

    @Test
    @DisplayName("nothing is exposed with an empty include list")
    void emptyIncludesNothing() {
        assertThat(EndpointExposure.of("", "").exposes("health")).isFalse();
        assertThat(EndpointExposure.of(null, null).exposes("health")).isFalse();
    }
}
