package io.github.excalibase.http;

import io.github.excalibase.security.BlockingHop;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Notes the thread each request is on at the first point after the blocking hop; on only for the tests that ask. */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
@Requires(property = "test.record-threads", value = "true")
public class ThreadRecorder implements Ordered {

    static final Map<String, String> THREADS = new ConcurrentHashMap<>();

    @RequestFilter
    public void record(HttpRequest<?> request) {
        THREADS.put(request.getMethodName() + " " + request.getPath(), Thread.currentThread().getName());
    }

    @Override
    public int getOrder() {
        return BlockingHop.ORDER + 1;
    }
}
