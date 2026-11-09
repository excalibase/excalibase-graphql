package io.github.excalibase.overload;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Answers 503 instead of queueing work the container cannot hold: when the heap
 * is nearly full after GC, or when too many requests are already in flight.
 */
public class OverloadShedFilter extends OncePerRequestFilter {

    private static final String BODY = "{\"errors\":[{\"message\":\"Server overloaded, retry shortly\","
            + "\"extensions\":{\"code\":\"overloaded\"}}]}";

    private final InFlightLimiter limiter;
    private final HeapPressure heapPressure;
    private final int retryAfterSeconds;

    public OverloadShedFilter(InFlightLimiter limiter, HeapPressure heapPressure, int retryAfterSeconds) {
        this.limiter = limiter;
        this.heapPressure = heapPressure;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator")
                || "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (heapPressure.isHigh() || !acquire()) {
            shed(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            limiter.release();
        }
    }

    private boolean acquire() {
        try {
            return limiter.tryAcquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void shed(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter().write(BODY);
    }
}
