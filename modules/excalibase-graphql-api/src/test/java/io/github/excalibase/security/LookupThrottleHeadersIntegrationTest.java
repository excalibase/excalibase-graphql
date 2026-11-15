package io.github.excalibase.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import io.github.excalibase.support.StubControlPlane;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EXC-722: the 429 of a throttled project lookup is readable by a browser and carries the security headers. */
class LookupThrottleHeadersIntegrationTest extends EngineIntegrationTest {

    private static final String ORIGIN = "https://app.example.com";
    private static final AtomicInteger NEXT = new AtomicInteger();

    /** A control plane that knows no project: every lookup is a 404, so each new id costs a lookup. */
    static final StubControlPlane provisioning = new StubControlPlane().start();

    @AfterAll
    static void teardown() {
        provisioning.close();
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.isolated());
        properties.put("app.database-type", "postgres");
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.security.auth.hmac-secret", "0123456789abcdef0123456789abcdef");
        properties.put("app.security.multi-tenant.provisioning-url", provisioning.url("/api"));
        properties.put("app.security.multi-tenant.provisioning-pat", "test-pat");
        properties.put("app.security.multi-tenant.lookup-rate-per-second", "1");
        properties.put("app.security.rls.policy-url", provisioning.url("/api"));
        properties.put("app.security.rls.policy-pat", "test-pat");
        properties.put("app.tenant-db.sslmode", "disable");
        return properties;
    }

    /** Asks about made-up projects, one lookup each, until an answer with {@code status} comes back. */
    private HttpAnswer untilAnswered(int status, IntFunction<HttpAnswer> ask) {
        for (int attempt = 0; attempt < 30; attempt++) {
            HttpAnswer answer = ask.apply(NEXT.incrementAndGet());
            if (answer.status() == status) {
                return answer;
            }
        }
        throw new AssertionError("no " + status + " after 30 lookups of unknown projects");
    }

    @Test
    @DisplayName("a throttled lookup answers 429 with CORS for the asking origin, Vary and the security headers")
    void throttledAnswerIsReadableByABrowser() {
        HttpAnswer answer = untilAnswered(429, id -> http.post("/unknown-" + id + "/graphql",
                "{\"query\":\"{ __typename }\"}", "Origin", ORIGIN, "Content-Type", "application/json"));

        assertThat(answer.header("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
        assertThat(String.join(",", answer.headers().allValues("Vary"))).contains("Origin");
        assertThat(answer.header("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("a throttled preflight is answered 204 with the allowed methods and no caching")
    void throttledPreflightIsAnswered() {
        HttpAnswer answer = untilAnswered(204, id -> http.send("OPTIONS", "/unknown-p" + id + "/graphql", null,
                "Origin", ORIGIN, "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "authorization"));

        assertThat(answer.header("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
        assertThat(answer.header("Access-Control-Allow-Methods")).contains("POST");
        assertThat(answer.header("Access-Control-Allow-Headers")).isEqualTo("authorization");
        assertThat(answer.header("Access-Control-Max-Age")).isEqualTo("0");
    }
}
