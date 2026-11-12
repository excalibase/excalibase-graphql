package io.github.excalibase.security;

import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.filter.FilterPatternStyle;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Moves the rest of the filter chain (and the handler) onto the blocking executor, because the security filters
 * read the database and the control plane. A WebSocket upgrade is left out: Micronaut invokes {@code @OnOpen} on
 * the thread the filters finished on, and a frame the client sends right after the 101 is dropped when that call
 * is still queued on another thread. Upgrades are always GETs on the two WebSocket paths, so the hop covers every
 * other method, and GETs anywhere else.
 */
public final class BlockingHop {

    /** Before the security filters (-100 and later), after the metrics filters. */
    public static final int ORDER = -900;

    static final String NOT_WEBSOCKET_PATH =
            "^(?!/[^/]+/graphql/?$)(?!/[^/]+/api/v1/realtime/?$).*$";

    private BlockingHop() {
    }

    @ServerFilter(patterns = "/**", methods = {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE,
            HttpMethod.OPTIONS, HttpMethod.HEAD})
    public static class AnyMethod implements Ordered {
        @RequestFilter
        @ExecuteOn(TaskExecutors.BLOCKING)
        public void hop() {
            // the annotation is the work
        }

        @Override
        public int getOrder() {
            return ORDER;
        }
    }

    @ServerFilter(patterns = NOT_WEBSOCKET_PATH, patternStyle = FilterPatternStyle.REGEX, methods = HttpMethod.GET)
    public static class Get implements Ordered {
        @RequestFilter
        @ExecuteOn(TaskExecutors.BLOCKING)
        public void hop() {
            // the annotation is the work
        }

        @Override
        public int getOrder() {
            return ORDER;
        }
    }
}
