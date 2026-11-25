package io.github.excalibase.http;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.server.HttpServerConfiguration;

/**
 * Refuses a body whose announced length is over the limit with 413, whatever its type: left to the route, a
 * form body on a JSON-only route was answered 415 without its size ever being judged (EXC-726).
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class RequestBodyLimitFilter implements Ordered {

    /** Behind the security headers (-950), so the refusal carries them like any other answer. */
    public static final int ORDER = -940;

    private final long maxBytes;

    public RequestBodyLimitFilter(HttpServerConfiguration server) {
        this.maxBytes = server.getMaxRequestSize();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        long announced = request.getContentLength();
        return announced > maxBytes ? DefaultErrorBody.response(request, HttpStatus.REQUEST_ENTITY_TOO_LARGE) : null;
    }
}
