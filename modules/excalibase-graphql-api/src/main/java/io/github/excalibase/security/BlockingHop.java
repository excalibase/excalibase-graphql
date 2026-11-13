package io.github.excalibase.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import java.util.concurrent.ExecutorService;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Runs the rest of the filter chain (and the handler) on the blocking executor, because the security filters read
 * the database and the control plane. It matches every request, whatever the method or path, a WebSocket upgrade
 * included; nothing that reads a database may run on an event loop thread. The chain is subscribed on the
 * executor, so every filter behind this one starts on a worker thread however the request arrived; an annotation
 * that only moved the first filter left the later ones on the event loop for some arrivals.
 */
@Filter(Filter.MATCH_ALL_PATTERN)
public class BlockingHop implements HttpServerFilter {

    /** Before the security filters (-100 and later), after the metrics filters. */
    public static final int ORDER = -900;

    private final Scheduler blocking;

    public BlockingHop(@Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this.blocking = Schedulers.fromExecutorService(executor);
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        return Flux.defer(() -> chain.proceed(request)).subscribeOn(blocking);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
