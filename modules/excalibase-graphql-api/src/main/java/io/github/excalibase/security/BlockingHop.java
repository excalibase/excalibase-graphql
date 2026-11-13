package io.github.excalibase.security;

import io.micronaut.core.order.Ordered;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Moves the rest of the filter chain (and the handler) onto the blocking executor, because the security filters
 * read the database and the control plane. It matches every request, whatever the method or path, a WebSocket
 * upgrade included; nothing that reads a database may run on an event loop thread.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class BlockingHop implements Ordered {

    /** Before the security filters (-100 and later), after the metrics filters. */
    public static final int ORDER = -900;

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
