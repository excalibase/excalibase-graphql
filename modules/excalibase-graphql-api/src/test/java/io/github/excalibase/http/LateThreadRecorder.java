package io.github.excalibase.http;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

/** After every security filter, where the upgrade handler is about to take the request over. */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
@Requires(property = "test.record-threads", value = "true")
public class LateThreadRecorder implements Ordered {

    @RequestFilter
    public void record(HttpRequest<?> request) {
        ThreadRecorder.THREADS.put("late " + request.getMethodName() + " " + request.getPath(),
                Thread.currentThread().getName());
    }

    @Override
    public int getOrder() {
        return WebSocketReturn.ORDER + 1;
    }
}
