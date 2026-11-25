package io.github.excalibase.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.server.netty.NettyHttpRequest;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Brings a WebSocket upgrade back to the connection's event loop once the security filters, which read databases
 * and run on a request thread, are done. Micronaut finishes the upgrade from the thread it is on: from a request
 * thread the frame decoder can be installed after the client's first frame has been read, and that frame is lost.
 */
@Filter(Filter.MATCH_ALL_PATTERN)
public class WebSocketReturn implements HttpServerFilter {

    /** Behind the last security filter (the route compatibility check, -60). */
    public static final int ORDER = -50;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        if (!WebSocketUpgrade.isRequested(request) || !(request instanceof NettyHttpRequest<?> netty)) {
            return chain.proceed(request);
        }
        return Flux.defer(() -> chain.proceed(request))
                .subscribeOn(Schedulers.fromExecutor(netty.getChannelHandlerContext().executor()));
    }
}
