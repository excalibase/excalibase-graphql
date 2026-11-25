package io.github.excalibase.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import org.junit.jupiter.api.Test;

/** A test that sets no port is given a free one, never the fixed ports a developer's own engine runs on. */
class TestPortsTest {

    @Test
    void theTestEnvironmentBindsNoFixedPort() {
        try (ApplicationContext context = ApplicationContext.builder().environments(Environment.TEST).build()) {
            context.getEnvironment().start();

            assertThat(context.getProperty("micronaut.server.port", Integer.class)).contains(-1);
            assertThat(context.getProperty("endpoints.all.port", Integer.class)).as("management port").contains(-1);
        }
    }
}
