package io.github.excalibase.observability;

import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records {@code http.server.requests} with a plain timer instead of the observation API, which
 * cost about 140 microseconds of every request. The tags match what the observation convention
 * produced: method, status, outcome, uri (the matched pattern, never the raw path), exception,
 * error, and the tenant, org and project labels.
 *
 * <p>Inert unless {@code enabled}.
 */
public class HttpRequestTimerFilter extends OncePerRequestFilter {

    static final String METRIC = "http.server.requests";
    /** Where {@link RejectedRequestRecorder} leaves the firewall's exception for the timer to name. */
    static final String REJECTED_ATTRIBUTE = HttpRequestTimerFilter.class.getName() + ".rejected";
    private static final String NONE = "none";

    private record TimerKey(String method, int status, String uri, String exception, TenantTags tenant) {
    }

    private static final Set<String> STANDARD_METHODS =
            Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");
    private static final String UNKNOWN = "UNKNOWN";

    private final MeterRegistry registry;
    private final boolean enabled;
    private final RoutePatternResolver routes;
    private final ConcurrentHashMap<TimerKey, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongTaskTimer> activeTimers = new ConcurrentHashMap<>();

    public HttpRequestTimerFilter(MeterRegistry registry, boolean enabled) {
        this(registry, enabled, RoutePatternResolver.NONE);
    }

    public HttpRequestTimerFilter(MeterRegistry registry, boolean enabled, RoutePatternResolver routes) {
        this.registry = registry;
        this.enabled = enabled;
        this.routes = routes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        long start = System.nanoTime();
        LongTaskTimer.Sample active = activeTimer(method(request)).start();
        Throwable thrown = null;
        try {
            chain.doFilter(request, response);
        } catch (Throwable failure) {
            thrown = failure;
            throw failure;
        } finally {
            active.stop();
            record(request, response, thrown, System.nanoTime() - start);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, Throwable thrown, long nanos) {
        int status = thrown != null && response.getStatus() < 400 ? 500 : response.getStatus();
        TimerKey key = new TimerKey(method(request), status, uri(request, status),
                exceptionName(request, thrown), TenantTags.of(request));
        timers.computeIfAbsent(key, this::newTimer).record(nanos, TimeUnit.NANOSECONDS);
    }

    private Timer newTimer(TimerKey key) {
        return Timer.builder(METRIC).tags(Tags.of(
                Tag.of("method", key.method()),
                Tag.of("status", Integer.toString(key.status())),
                Tag.of("uri", key.uri()),
                Tag.of("outcome", outcome(key.status())),
                Tag.of("exception", key.exception()),
                Tag.of("error", key.exception()),
                Tag.of("tenant.id", key.tenant().tenantId()),
                Tag.of("org.slug", key.tenant().orgSlug()),
                Tag.of("project.name", key.tenant().projectName()),
                Tag.of("org.name", key.tenant().orgName()))).register(registry);
    }

    /** Only the standard methods name a series; anything else is one series, so a client cannot mint more. */
    private static String method(HttpServletRequest request) {
        String method = request.getMethod();
        return method != null && STANDARD_METHODS.contains(method) ? method : UNKNOWN;
    }

    /** The in-flight gauge, tagged as the observation tagged it at the start of a request: only the method is known. */
    private LongTaskTimer activeTimer(String method) {
        return activeTimers.computeIfAbsent(method, known -> LongTaskTimer.builder(METRIC + ".active").tags(Tags.of(
                Tag.of("exception", NONE), Tag.of("method", known), Tag.of("org.name", NONE),
                Tag.of("org.slug", NONE), Tag.of("outcome", "SUCCESS"), Tag.of("project.name", NONE),
                Tag.of("status", "200"), Tag.of("tenant.id", NONE), Tag.of("uri", UNKNOWN))).register(registry));
    }

    /**
     * The matched pattern keeps cardinality bounded; unmatched requests collapse to a fixed word. A request
     * the security chain answered before routing gets the pattern its path would have matched.
     */
    private String uri(HttpServletRequest request, int status) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) {
            return pattern.toString();
        }
        String routed = routes.resolve(request);
        if (routed != null) {
            return routed;
        }
        if (status / 100 == 3) {
            return "REDIRECTION";
        }
        return status == 404 ? "NOT_FOUND" : UNKNOWN;
    }

    private static String exceptionName(HttpServletRequest request, Throwable thrown) {
        Throwable failure = thrown;
        if (failure == null && request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable recorded) {
            failure = recorded;
        }
        if (failure == null && request.getAttribute(REJECTED_ATTRIBUTE) instanceof Throwable rejected) {
            failure = rejected;
        }
        if (failure instanceof ServletException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure == null) {
            return NONE;
        }
        String simple = failure.getClass().getSimpleName();
        return simple.isEmpty() ? failure.getClass().getName() : simple;
    }

    private static String outcome(int status) {
        HttpStatus.Series series = HttpStatus.Series.resolve(status);
        return series == null ? "UNKNOWN" : switch (series) {
            case INFORMATIONAL -> "INFORMATIONAL";
            case SUCCESSFUL -> "SUCCESS";
            case REDIRECTION -> "REDIRECTION";
            case CLIENT_ERROR -> "CLIENT_ERROR";
            case SERVER_ERROR -> "SERVER_ERROR";
        };
    }
}
