package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
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

/**
 * Shutdown drains: a request already in flight when it starts completes, new connections are refused, and
 * readiness says OUT_OF_SERVICE meanwhile, as the earlier build's graceful shutdown did. The test owns its engine
 * and its threads: a test framework that also stops the engine at the end of the class would take the same locks
 * from another thread, and every wait here is bounded.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class GracefulShutdownIntegrationTest {

    private static Map<String, Object> properties(int managementPort) {
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
        properties.put("management.endpoints.web.exposure.include", "health");
        properties.put("management.endpoint.health.probes.enabled", "true");
        properties.put("endpoints.all.port", Integer.toString(managementPort));
        properties.put("test.held-endpoint", "true");
        return properties;
    }

    @Test
    @DisplayName("a request in flight when shutdown starts completes; new connections are refused; readiness drains")
    void inFlightRequestCompletes() throws Exception {
        int managementPort = FreePort.find();
        ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "graceful-shutdown-test");
            thread.setDaemon(true);
            return thread;
        });
        ApplicationContext context = ApplicationContext.run(properties(managementPort), "test");
        try {
            EmbeddedServer embedded = context.getBean(EmbeddedServer.class).start();
            int port = embedded.getPort();
            HeldEndpoint.reset();
            CompletableFuture<HttpAnswer> inFlight = CompletableFuture.supplyAsync(
                    () -> new EngineClient(URI.create("http://localhost:" + port + "/")).get("/test-proj/held"), threads);
            assertThat(HeldEndpoint.started.await(10, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> stopping = CompletableFuture.runAsync(context::stop, threads);
            Thread.sleep(1500);

            assertThat(stopping).as("the stop waits for the request").isNotDone();
            assertThat(inFlight).as("the request is not cut off").isNotDone();
            EngineClient management = new EngineClient(URI.create("http://localhost:" + managementPort + "/"));
            HttpAnswer readiness = management.get("/actuator/health/readiness");
            for (int attempt = 0; attempt < 50 && readiness.status() != 503; attempt++) {
                Thread.sleep(100);   // the drain begins a moment after stop is asked for, longer when the machine is busy
                readiness = management.get("/actuator/health/readiness");
            }
            assertThat(readiness.status()).isEqualTo(503);
            assertThat(readiness.body()).isEqualTo("{\"status\":\"OUT_OF_SERVICE\"}");
            assertThat(inFlight).as("the request is still held while readiness drains").isNotDone();
            assertThatThrownBy(() -> new EngineClient(URI.create("http://localhost:" + port + "/")).get("/test-proj/held"))
                    .as("no new connection is accepted").isNotNull();

            HeldEndpoint.release.countDown();

            HttpAnswer answer = inFlight.get(10, TimeUnit.SECONDS);
            assertThat(answer.status()).isEqualTo(200);
            assertThat(answer.body()).isEqualTo("finished");
            stopping.get(20, TimeUnit.SECONDS);
        } finally {
            HeldEndpoint.release.countDown();
            threads.shutdownNow();
            if (context.isRunning()) {
                context.stop();
            }
        }
    }
}
