package io.github.excalibase.http;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

/**
 * Refuses request paths the servlet stack refused, before routing and before any other filter but the request
 * timer, so the refusal is counted. Both ports.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class RequestFirewallFilter implements Ordered {

    /** Right behind the request timer (-1000). */
    public static final int ORDER = -990;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        String path = RequestTarget.rawPath(request);
        if (RequestTargetRules.refusedByServer(path)) {
            return TomcatErrorPage.badRequest();
        }
        if (RequestTargetRules.refusedByFirewall(path)) {
            throw new RequestRejectedException("The request target is not allowed");
        }
        return null;
    }
}
