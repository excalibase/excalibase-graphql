package io.github.excalibase.observability.actuator;

/** Constants every actuator endpoint shares. */
final class ActuatorEndpoint {

    /** The media type Spring Boot gave actuator answers; clients and tests of the earlier build match on it. */
    static final String JSON = "application/vnd.spring-boot.actuator.v3+json";

    /** Settings prefix of these endpoints, apart from {@code endpoints.*} which switches Micronaut's own off. */
    static final String PREFIX = "excalibase.actuator";

    private ActuatorEndpoint() {
    }
}
