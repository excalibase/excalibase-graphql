package io.github.excalibase.observability.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OtlpEndpointsTest {

    @Test
    @DisplayName("without a collector or an endpoint of its own, traces are not exported")
    void none() {
        assertThat(new OtlpEndpoints("", "").traces()).isEmpty();
        assertThat(new OtlpEndpoints("  ", " ").traces()).isEmpty();
    }

    @Test
    @DisplayName("a collector gets traces at its /v1/traces path, a trailing slash ignored")
    void collector() {
        assertThat(new OtlpEndpoints("http://collector:4318/", "").traces()).contains("http://collector:4318/v1/traces");
    }

    @Test
    @DisplayName("a tracing endpoint of its own wins, alone or beside a collector")
    void own() {
        assertThat(new OtlpEndpoints("http://collector:4318", "http://tempo:4318/v1/traces").traces())
                .contains("http://tempo:4318/v1/traces");
        assertThat(new OtlpEndpoints("", "http://tempo:4318/v1/traces").traces()).contains("http://tempo:4318/v1/traces");
    }
}
