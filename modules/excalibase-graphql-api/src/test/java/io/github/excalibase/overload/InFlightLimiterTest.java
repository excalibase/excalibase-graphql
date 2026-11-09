package io.github.excalibase.overload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InFlightLimiterTest {

    @Test
    @DisplayName("the cap scales with the processor count")
    void capScalesWithCpus() {
        assertThat(InFlightLimiter.forCpus(4, 16, Duration.ZERO).capacity()).isEqualTo(64);
        assertThat(InFlightLimiter.forCpus(1, 16, Duration.ZERO).capacity()).isEqualTo(16);
    }

    @Test
    @DisplayName("acquire fails once the cap is reached and succeeds again after release")
    void acquireRelease() throws Exception {
        InFlightLimiter limiter = new InFlightLimiter(2, Duration.ZERO);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        limiter.release();
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("a cap below one is rejected")
    void invalidCap() {
        assertThatThrownBy(() -> new InFlightLimiter(0, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
