package io.github.excalibase.overload;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetricValueTest {

    private static String body(String value) {
        return "{\"name\":\"tomcat.connections.config.max\",\"baseUnit\":\"connections\","
                + "\"measurements\":[{\"statistic\":\"VALUE\",\"value\":" + value + "}],"
                + "\"availableTags\":[{\"tag\":\"name\",\"values\":[\"http-nio-auto-9\"]}]}";
    }

    @Test
    void readsTheMeasuredValue() {
        assertThat(MetricValue.of(body("8.0"))).isEqualTo(8.0);
    }

    @Test
    void aBoundOf8IsNotSatisfiedByTomcatsDefaultOf8192() {
        assertThat(MetricValue.of(body("8192.0"))).isEqualTo(8192.0).isNotEqualTo((double) (4 * 2));
    }

    @Test
    void aBoundOf8IsNotSatisfiedBy80Or800() {
        assertThat(MetricValue.of(body("80.0"))).isEqualTo(80.0);
        assertThat(MetricValue.of(body("800.0"))).isEqualTo(800.0);
    }
}
