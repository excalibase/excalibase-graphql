package io.github.excalibase.overload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeapPressureTest {

    @Test
    @DisplayName("high once the live share after GC passes the threshold")
    void thresholdCompare() {
        assertThat(new RetainedHeapPressure(85, () -> 0.86).isHigh()).isTrue();
        assertThat(new RetainedHeapPressure(85, () -> 0.85).isHigh()).isFalse();
        assertThat(new RetainedHeapPressure(85, () -> 0.10).isHigh()).isFalse();
    }

    @Test
    @DisplayName("a threshold outside 1..100 is rejected")
    void invalidThreshold() {
        assertThatThrownBy(() -> new RetainedHeapPressure(0, () -> 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetainedHeapPressure(101, () -> 0.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the JVM reading is a share between 0 and 1")
    void jvmReadingIsAShare() {
        double share = RetainedHeapPressure.jvmRetainedShare();
        assertThat(share).isBetween(0.0, 1.0);
    }
}
