package io.github.excalibase.observability.actuator;

import java.util.Arrays;
import java.util.Comparator;

/** The status of a health answer; DOWN is worse than OUT_OF_SERVICE, which is worse than UP, as in Spring Boot. */
public enum HealthStatus {
    DOWN(503), OUT_OF_SERVICE(503), UP(200);

    private final int httpStatus;

    HealthStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** The worst of the given statuses; UP when there are none. */
    public static HealthStatus combine(HealthStatus... statuses) {
        return Arrays.stream(statuses).min(Comparator.naturalOrder()).orElse(UP);
    }
}
