package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The drain is bounded: a request still held when the timeout passes does not keep the process up. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class GracefulShutdownTimeoutIntegrationTest {

    @Test
    @DisplayName("shutdown gives up on a request held past the timeout instead of waiting for it")
    void stopsAfterTheTimeout() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent");
        properties.put("spring.datasource.username", "fake");
        properties.put("spring.datasource.password", "fake");
        properties.put("app.project-id", "test-proj");
        properties.put("app.database-type", "postgres");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("app.nats.enabled", "false");
        properties.put("micronaut.server.port", "-1");
        properties.put("endpoints.all.port", "-1");
        properties.put("micronaut.lifecycle.graceful-shutdown.grace-period", "PT1S");
        properties.put("test.held-endpoint", "true");
        ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "graceful-shutdown-timeout-test");
            thread.setDaemon(true);
            return thread;
        });
        ApplicationContext context = ApplicationContext.run(properties, "test");
        try {
            EmbeddedServer embedded = context.getBean(EmbeddedServer.class).start();
            int port = embedded.getPort();
            HeldEndpoint.reset();
            CompletableFuture.runAsync(() -> {
                try {
                    new EngineClient(URI.create("http://localhost:" + port + "/")).get("/test-proj/held");
                } catch (RuntimeException cutOff) {
                    // the connection is closed under the request: expected
                }
            }, threads);
            assertThat(HeldEndpoint.started.await(10, TimeUnit.SECONDS)).isTrue();

            long begun = System.nanoTime();
            CompletableFuture.runAsync(context::stop, threads).get(30, TimeUnit.SECONDS);
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begun);

            assertThat(seconds).isLessThan(5);
        } finally {
            HeldEndpoint.release.countDown();
            threads.shutdownNow();
            if (context.isRunning()) {
                context.stop();
            }
        }
    }
}
