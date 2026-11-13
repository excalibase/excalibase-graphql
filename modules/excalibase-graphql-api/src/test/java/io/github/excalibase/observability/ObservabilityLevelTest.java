package io.github.excalibase.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ObservabilityLevelTest {

    @Test
    @DisplayName("an unset level is full")
    void unsetIsFull() {
        assertThat(ObservabilityLevel.parse(null)).isEqualTo(ObservabilityLevel.FULL);
        assertThat(ObservabilityLevel.parse("  ")).isEqualTo(ObservabilityLevel.FULL);
    }

    @Test
    @DisplayName("levels parse without regard to case or surrounding blanks")
    void caseInsensitive() {
        assertThat(ObservabilityLevel.parse("lite")).isEqualTo(ObservabilityLevel.LITE);
        assertThat(ObservabilityLevel.parse(" OFF ")).isEqualTo(ObservabilityLevel.OFF);
        assertThat(ObservabilityLevel.parse("Full")).isEqualTo(ObservabilityLevel.FULL);
    }

    @Test
    @DisplayName("an unknown level fails with the valid choices")
    void unknownFails() {
        assertThatThrownBy(() -> ObservabilityLevel.parse("verbose"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("excalibase.observability.level")
                .hasMessageContaining("verbose")
                .hasMessageContaining("full, lite, off");
    }

    @Test
    @DisplayName("full times requests and traces them; lite only times; off does neither")
    void whatEachLevelKeeps() {
        assertThat(ObservabilityLevel.FULL.timesRequests()).isTrue();
        assertThat(ObservabilityLevel.FULL.tracesRequests()).isTrue();
        assertThat(ObservabilityLevel.LITE.timesRequests()).isTrue();
        assertThat(ObservabilityLevel.LITE.tracesRequests()).isFalse();
        assertThat(ObservabilityLevel.OFF.timesRequests()).isFalse();
        assertThat(ObservabilityLevel.OFF.tracesRequests()).isFalse();
    }
}
