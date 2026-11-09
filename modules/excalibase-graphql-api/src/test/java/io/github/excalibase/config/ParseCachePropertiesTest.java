package io.github.excalibase.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParseCachePropertiesTest {

    @Test
    void validShareIsAccepted() {
        assertThat(new ParseCacheProperties(0.02).heapShare()).isEqualTo(0.02);
    }

    @Test
    void invalidShareFailsInsteadOfFallingBack() {
        for (double invalid : new double[]{0, -1, 0.9, Double.NaN}) {
            assertThatThrownBy(() -> new ParseCacheProperties(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
