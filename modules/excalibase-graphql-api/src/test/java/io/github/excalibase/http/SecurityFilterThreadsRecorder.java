package io.github.excalibase.http;

import io.github.excalibase.cors.CorsFilter;
import io.github.excalibase.security.JwtAuthFilter;
import io.github.excalibase.security.KnownProjectFilter;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Notes, per request carrying an {@code X-Probe} header, the thread at three points of the security chain: before
 * the project check, between CORS and authentication, and after authentication. On only for the tests that ask.
 */
public final class SecurityFilterThreadsRecorder {

    private SecurityFilterThreadsRecorder() {
    }

    static final Map<String, List<String>> THREADS = new ConcurrentHashMap<>();

    private static void note(HttpRequest<?> request, String point) {
        String probe = request.getHeaders().get("X-Probe");
        if (probe != null) {
            THREADS.computeIfAbsent(probe, key -> new CopyOnWriteArrayList<>())
                    .add(point + "@" + Thread.currentThread().getName());
        }
    }

    @ServerFilter(Filter.MATCH_ALL_PATTERN)
    @Requires(property = "test.record-security-threads", value = "true")
    public static class BeforeProject implements Ordered {
        @RequestFilter
        public void record(HttpRequest<?> request) {
            note(request, "before-project");
        }

        @Override
        public int getOrder() {
            return KnownProjectFilter.ORDER - 1;
        }
    }

    @ServerFilter(Filter.MATCH_ALL_PATTERN)
    @Requires(property = "test.record-security-threads", value = "true")
    public static class BetweenCorsAndAuth implements Ordered {
        @RequestFilter
        public void record(HttpRequest<?> request) {
            note(request, "between-cors-and-auth");
        }

        @Override
        public int getOrder() {
            return (CorsFilter.ORDER + JwtAuthFilter.ORDER) / 2;
        }
    }

    @ServerFilter(Filter.MATCH_ALL_PATTERN)
    @Requires(property = "test.record-security-threads", value = "true")
    public static class AfterAuth implements Ordered {
        @RequestFilter
        public void record(HttpRequest<?> request) {
            note(request, "after-auth");
        }

        @Override
        public int getOrder() {
            return JwtAuthFilter.ORDER + 1;
        }
    }
}
