package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The drain is bounded: a request still held when the timeout passes does not keep the process up. */
class GracefulShutdownTimeoutIntegrationTest extends EngineIntegrationTest {

    @Inject
    ApplicationContext context;

    @Override
    public Map<String, String> getProperties() {
        return Map.ofEntries(
                Map.entry("app.security.multi-tenant.provisioning-url", "http://localhost:59998/api"),
                Map.entry("app.security.auth.hmac-secret", "shutdown-test-hmac-secret-0123456789abcdef"),
                Map.entry("app.nats.enabled", "false"),
                Map.entry("micronaut.lifecycle.graceful-shutdown.grace-period", "PT1S"),
                Map.entry("test.held-endpoint", "true"));
    }

    @Test
    @DisplayName("shutdown gives up on a request held past the timeout instead of waiting for it")
    void stopsAfterTheTimeout() throws Exception {
        HeldEndpoint.reset();
        CompletableFuture.runAsync(() -> {
            try {
                http.get("/test-proj/held");
            } catch (RuntimeException cutOff) {
                // the connection is closed under the request: expected
            }
        });
        assertThat(HeldEndpoint.started.await(10, TimeUnit.SECONDS)).isTrue();

        long begun = System.nanoTime();
        context.stop();
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begun);

        assertThat(seconds).isLessThan(5);
        HeldEndpoint.release.countDown();
    }
}
