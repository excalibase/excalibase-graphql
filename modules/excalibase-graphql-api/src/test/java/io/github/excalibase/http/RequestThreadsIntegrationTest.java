package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.scheduling.TaskExecutors;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Requests run on virtual threads unless spring.threads.virtual.enabled=false, and the thread meters say which. */
class RequestThreadsIntegrationTest {

    private static final Map<String, Object> BASE = Map.of(
            "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
            "spring.datasource.username", "fake",
            "spring.datasource.password", "fake",
            "app.project-id", "test-proj",
            "app.security.jwt-enabled", "false",
            "app.security.insecure-dev-mode", "true",
            "app.nats.enabled", "false",
            "micronaut.server.port", "-1",
            "endpoints.all.port", "-1");

    private static String threadName(ApplicationContext context) throws Exception {
        ExecutorService requests = context.getBean(ExecutorService.class, Qualifiers.byName(TaskExecutors.BLOCKING));
        return requests.submit(() -> Thread.currentThread().getName()).get();
    }

    private static double gauge(ApplicationContext context, String name) {
        return context.getBean(MeterRegistry.class).get(name).gauge().value();
    }

    @Test
    @DisplayName("by default handlers are virtual threads named as before and there is no thread ceiling")
    void virtualByDefault() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(BASE)) {
            assertThat(threadName(context)).startsWith("tomcat-handler-");
            assertThat(gauge(context, "tomcat.threads.config.max")).isEqualTo(-1);
            assertThat(gauge(context, "tomcat.threads.current")).isEqualTo(-1);
            assertThat(gauge(context, "tomcat.threads.busy")).isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("with spring.threads.virtual.enabled=false handlers are platform threads capped by server.tomcat.threads.max")
    void platformWhenAsked() throws Exception {
        Map<String, Object> properties = new java.util.HashMap<>(BASE);
        properties.put("spring.threads.virtual.enabled", "false");
        properties.put("server.tomcat.threads.max", "17");
        properties.put("micronaut.server.port", "10432");
        try (ApplicationContext context = ApplicationContext.run(properties)) {
            assertThat(threadName(context)).matches("http-nio-10432-exec-\\d+");
            assertThat(gauge(context, "tomcat.threads.config.max")).isEqualTo(17);
            assertThat(gauge(context, "tomcat.threads.current")).isGreaterThanOrEqualTo(1);
        }
    }
}
