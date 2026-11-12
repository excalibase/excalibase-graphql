package io.github.excalibase.overload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads the single measurement of an {@code /actuator/metrics/<name>} body as a number, never as text. */
final class MetricValue {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MetricValue() {
    }

    static double of(String actuatorMetricJson) {
        try {
            return JSON.readTree(actuatorMetricJson).path("measurements").path(0).path("value").asDouble(Double.NaN);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalArgumentException("not a metric body: " + actuatorMetricJson, unreadable);
        }
    }
}
