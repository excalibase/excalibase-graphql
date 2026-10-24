package io.github.excalibase.observability.requests;

import io.github.excalibase.security.KnownProjectFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * On the internal metrics port: answers {@code GET /metrics} with the scrape and
 * refuses everything else before it reaches the application. On every other port:
 * times project-scoped GraphQL and REST requests of known projects.
 */
public class RequestMetricsFilter extends OncePerRequestFilter {

    static final String METRICS_PATH = "/metrics";
    private static final String SCRAPE_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final RequestMetrics metrics;
    private final int metricsPort;

    public RequestMetricsFilter(RequestMetrics metrics, int metricsPort) {
        this.metrics = metrics;
        this.metricsPort = metricsPort;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getLocalPort() == metricsPort) {
            serveInternal(request, response);
            return;
        }
        Optional<RequestFamily> family = RequestFamily.of(request.getRequestURI());
        if (family.isEmpty() || isWebSocketUpgrade(request)) {
            chain.doFilter(request, response);
            return;
        }
        long started = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            record(request, response, family.get(), Duration.ofNanos(System.nanoTime() - started), failed);
        }
    }

    private void serveInternal(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"GET".equals(request.getMethod()) || !METRICS_PATH.equals(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        byte[] body = metrics.scrape().getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(SCRAPE_CONTENT_TYPE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private void record(HttpServletRequest request, HttpServletResponse response, RequestFamily family,
                        Duration duration, boolean failed) {
        // Set only after the project passed the known-project check, so random paths create no series.
        if (!(request.getAttribute(KnownProjectFilter.KNOWN_PROJECT_ATTR) instanceof String projectId)) {
            return;
        }
        RequestOutcome outcome = failed ? RequestOutcome.SERVER_ERROR
                : RequestOutcome.of(response.getStatus(),
                        Boolean.TRUE.equals(request.getAttribute(RequestMetrics.GRAPHQL_ERRORS_ATTR)));
        metrics.recordRequest(projectId, family, outcome, duration);
    }

    private static boolean isWebSocketUpgrade(HttpServletRequest request) {
        return "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));
    }
}
