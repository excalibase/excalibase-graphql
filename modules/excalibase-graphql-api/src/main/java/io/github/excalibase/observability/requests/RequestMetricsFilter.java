package io.github.excalibase.observability.requests;

import io.github.excalibase.security.KnownProjectFilter;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;

import java.time.Duration;
import java.util.Optional;

/**
 * On the internal metrics port: answers {@code GET /metrics} with the scrape and refuses everything else
 * before it reaches the application. On every other port: times project-scoped GraphQL and REST requests
 * of known projects.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
@Requires(property = "app.request-metrics.enabled", value = "true")
public class RequestMetricsFilter implements Ordered {

    static final String METRICS_PATH = "/metrics";
    private static final String SCRAPE_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    private static final String STARTED = "excalibase.requestMetrics.started";

    private final RequestMetrics metrics;
    private final int metricsPort;

    public RequestMetricsFilter(RequestMetrics metrics, @Value("${app.request-metrics.port:9464}") int metricsPort) {
        this.metrics = metrics;
        this.metricsPort = metricsPort;
    }

    @Override
    public int getOrder() {
        return -2000;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        if (request.getServerAddress() != null && request.getServerAddress().getPort() == metricsPort) {
            return serveInternal(request);
        }
        Optional<RequestFamily> family = RequestFamily.of(request.getPath());
        if (family.isPresent() && !isWebSocketUpgrade(request)) {
            request.getAttributes().put(STARTED, System.nanoTime());
        }
        return null;
    }

    private HttpResponse<?> serveInternal(HttpRequest<?> request) {
        if (request.getMethod() != HttpMethod.GET || !METRICS_PATH.equals(request.getPath())) {
            return HttpResponse.status(HttpStatus.NOT_FOUND);
        }
        return HttpResponse.ok(metrics.scrape()).contentType(SCRAPE_CONTENT_TYPE);
    }

    @ResponseFilter
    public void record(HttpRequest<?> request, HttpResponse<?> response, @Nullable Throwable thrown) {
        Object started = request.getAttribute(STARTED).orElse(null);
        // Set only after the project passed the known-project check, so random paths create no series.
        if (!(started instanceof Long startNanos)
                || !(request.getAttribute(KnownProjectFilter.KNOWN_PROJECT_ATTR).orElse(null) instanceof String projectId)) {
            return;
        }
        RequestFamily family = RequestFamily.of(request.getPath()).orElse(null);
        if (family == null) {
            return;
        }
        boolean failed = thrown != null;
        RequestOutcome outcome = failed ? RequestOutcome.SERVER_ERROR
                : RequestOutcome.of(response.code(),
                        Boolean.TRUE.equals(request.getAttribute(RequestMetrics.GRAPHQL_ERRORS_ATTR).orElse(null)));
        metrics.recordRequest(projectId, family, outcome, Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private static boolean isWebSocketUpgrade(HttpRequest<?> request) {
        return "websocket".equalsIgnoreCase(request.getHeaders().get("Upgrade"));
    }
}
