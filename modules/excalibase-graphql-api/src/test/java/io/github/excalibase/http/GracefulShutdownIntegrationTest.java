package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Shutdown drains: a request already in flight when it starts completes, new connections are refused, and
 * readiness says OUT_OF_SERVICE meanwhile, as the earlier build's graceful shutdown did.
 */
class GracefulShutdownIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();

    @Inject
    ApplicationContext context;

    @Override
    public Map<String, String> getProperties() {
        return Map.ofEntries(
                Map.entry("app.security.multi-tenant.provisioning-url", "http://localhost:59998/api"),
                Map.entry("app.security.auth.hmac-secret", "shutdown-test-hmac-secret-0123456789abcdef"),
                Map.entry("app.nats.enabled", "false"),
                Map.entry("management.endpoints.web.exposure.include", "health"),
                Map.entry("management.endpoint.health.probes.enabled", "true"),
                Map.entry("endpoints.all.port", Integer.toString(MANAGEMENT_PORT)),
                Map.entry("test.held-endpoint", "true"));
    }

    @Test
    @DisplayName("a request in flight when shutdown starts completes; new connections are refused; readiness drains")
    void inFlightRequestCompletes() throws Exception {
        HeldEndpoint.reset();
        CompletableFuture<HttpAnswer> inFlight = CompletableFuture.supplyAsync(() -> http.get("/test-proj/held"));
        assertThat(HeldEndpoint.started.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(context::stop);
        Thread.sleep(1500);

        assertThat(stopping).as("the stop waits for the request").isNotDone();
        assertThat(inFlight).as("the request is not cut off").isNotDone();
        assertThatThrownBy(() -> new EngineClient(URI.create("http://localhost:" + server.getPort() + "/")).get("/test-proj/held"))
                .as("no new connection is accepted").isNotNull();
        HttpAnswer readiness = new EngineClient(URI.create("http://localhost:" + MANAGEMENT_PORT + "/")).get("/actuator/health/readiness");
        assertThat(readiness.status()).isEqualTo(503);
        assertThat(readiness.body()).isEqualTo("{\"status\":\"OUT_OF_SERVICE\"}");

        HeldEndpoint.release.countDown();

        HttpAnswer answer = inFlight.get(10, TimeUnit.SECONDS);
        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo("finished");
        stopping.get(10, TimeUnit.SECONDS);
    }
}
