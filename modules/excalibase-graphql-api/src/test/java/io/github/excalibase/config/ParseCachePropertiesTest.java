package io.github.excalibase.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParseCachePropertiesTest {

    @Test
    @DisplayName("a valid share of the heap is accepted")
    void validShareIsAccepted() {
        assertThat(new AppConfig().parsedQueries(0.02)).isNotNull();
    }

    @Test
    @DisplayName("an invalid share fails start-up instead of falling back")
    void invalidShareFailsInsteadOfFallingBack() {
        for (double invalid : new double[]{0, -1, 0.9, Double.NaN}) {
            assertThatThrownBy(() -> new AppConfig().parsedQueries(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
