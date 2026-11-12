package io.github.excalibase.observability;

import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Records {@code http.server.requests} with a plain timer: method, status, outcome, uri (the matched pattern,
 * never the raw path), exception, error, and the tenant, org and project labels. Inert at level {@code off}.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
@Requires(property = "excalibase.observability.level", notEquals = "off")
public class HttpRequestTimerFilter implements Ordered {

    static final String METRIC = "http.server.requests";
    private static final String NONE = "none";
    private static final String START = "excalibase.timer.start";
    private static final String ACTIVE = "excalibase.timer.active";

    private record TimerKey(String method, int status, String uri, String exception, TenantTags tenant) {
    }

    private static final Set<String> STANDARD_METHODS =
            Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");
    private static final String UNKNOWN = "UNKNOWN";

    private final MeterRegistry registry;
    private final ConcurrentHashMap<TimerKey, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongTaskTimer> activeTimers = new ConcurrentHashMap<>();

    public HttpRequestTimerFilter(MeterRegistry registry,
                                  @Value("${excalibase.observability.level:full}") String level) {
        ObservabilityLevel.parse(level);
        this.registry = registry;
    }

    @Override
    public int getOrder() {
        return -1000;
    }

    @RequestFilter
    public void start(HttpRequest<?> request) {
        request.getAttributes().put(ACTIVE, activeTimer(method(request)).start());
        request.getAttributes().put(START, System.nanoTime());
    }

    @ResponseFilter
    public void end(HttpRequest<?> request, HttpResponse<?> response, @Nullable Throwable thrown) {
        Object started = request.getAttribute(START).orElse(null);
        if (!(started instanceof Long startNanos)) {
            return;
        }
        if (request.getAttribute(ACTIVE).orElse(null) instanceof LongTaskTimer.Sample sample) {
            sample.stop();
        }
        int status = thrown != null && response.code() < 400 ? 500 : response.code();
        TimerKey key = new TimerKey(method(request), status, uri(request, status),
                exceptionName(thrown), TenantTags.of(request));
        timers.computeIfAbsent(key, this::newTimer).record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
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
    private static String method(HttpRequest<?> request) {
        String method = request.getMethodName();
        return method != null && STANDARD_METHODS.contains(method) ? method : UNKNOWN;
    }

    private LongTaskTimer activeTimer(String method) {
        return activeTimers.computeIfAbsent(method, known -> LongTaskTimer.builder(METRIC + ".active").tags(Tags.of(
                Tag.of("exception", NONE), Tag.of("method", known), Tag.of("org.name", NONE),
                Tag.of("org.slug", NONE), Tag.of("outcome", "SUCCESS"), Tag.of("project.name", NONE),
                Tag.of("status", "200"), Tag.of("tenant.id", NONE), Tag.of("uri", UNKNOWN))).register(registry));
    }

    /** The matched pattern keeps cardinality bounded; unmatched requests collapse to a fixed word. */
    private static String uri(HttpRequest<?> request, int status) {
        String pattern = request.getAttribute(HttpAttributes.URI_TEMPLATE, String.class).orElse(null);
        if (pattern != null) {
            return pattern;
        }
        if (status / 100 == 3) {
            return "REDIRECTION";
        }
        return status == 404 ? "NOT_FOUND" : UNKNOWN;
    }

    private static String exceptionName(Throwable failure) {
        if (failure == null) {
            return NONE;
        }
        String simple = failure.getClass().getSimpleName();
        return simple.isEmpty() ? failure.getClass().getName() : simple;
    }

    private static String outcome(int status) {
        return switch (status / 100) {
            case 1 -> "INFORMATIONAL";
            case 2 -> "SUCCESS";
            case 3 -> "REDIRECTION";
            case 4 -> "CLIENT_ERROR";
            case 5 -> "SERVER_ERROR";
            default -> "UNKNOWN";
        };
    }
}
