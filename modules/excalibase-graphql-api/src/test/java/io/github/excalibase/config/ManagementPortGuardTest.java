package io.github.excalibase.config;

import io.github.excalibase.support.EngineProperties;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagementPortGuardTest {

    @Test
    @DisplayName("a management port equal to the public port is refused, naming both settings")
    void equalPortsAreRefused() {
        assertThatThrownBy(() -> new ManagementPortGuard(8081, 8081).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MANAGEMENT_SERVER_PORT")
                .hasMessageContaining("SERVER_PORT")
                .hasMessageContaining("8081");
    }

    @Test
    @DisplayName("different ports, or no management port, or a random public port, start")
    void otherCombinationsStart() {
        assertThatCode(() -> new ManagementPortGuard(10000, 8081).check()).doesNotThrowAnyException();
        assertThatCode(() -> new ManagementPortGuard(10000, -1).check()).doesNotThrowAnyException();
        assertThatCode(() -> new ManagementPortGuard(-1, -1).check()).doesNotThrowAnyException();
        assertThatCode(() -> new ManagementPortGuard(-1, 8081).check()).doesNotThrowAnyException();
    }

    /** A real context start, so the property names behind the check are pinned too. */
    @Test
    @DisplayName("an engine started with equal ports refuses to start")
    void equalPortsRefuseAContextStart() {
        Map<String, Object> properties = new HashMap<>(EngineProperties.unreachableDatabase("p1"));
        properties.put("micronaut.server.port", "18555");
        properties.put("endpoints.all.port", "18555");

        assertThatThrownBy(() -> {
            try (ApplicationContext context = ApplicationContext.run(properties)) {
                context.getBeanDefinitions(Object.class);
            }
        }).hasStackTraceContaining("MANAGEMENT_SERVER_PORT");
    }
}
