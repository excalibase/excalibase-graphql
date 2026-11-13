package io.github.excalibase.http;

import io.github.excalibase.observability.HttpRequestFacts;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.web.router.Router;

/**
 * Refuses request paths the servlet stack refused, before routing and before any other filter but the request
 * timer, so the refusal is counted. Both ports.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class RequestFirewallFilter implements Ordered {

    /** Right behind the request timer (-1000). */
    public static final int ORDER = -990;

    private final Router router;

    public RequestFirewallFilter(Router router) {
        this.router = router;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        HttpResponse<?> refusedMethod = RequestTargetRules.refusalForMethod(request.getMethodName());
        if (refusedMethod != null) {
            return refusedMethod;
        }
        String path = RequestTarget.rawPath(request);
        if (RequestTargetRules.refusedByServer(path)) {
            return TomcatErrorPage.badRequest();
        }
        if (RequestTargetRules.refusedByFirewall(path)) {
            RequestRejectedException rejection = new RequestRejectedException("The request target is not allowed");
            request.setAttribute(HttpRequestFacts.EXCEPTION_ATTRIBUTE, rejection);
            nameRoute(request, path);
            throw rejection;
        }
        return null;
    }

    /** The route the path would have matched, so the counted request carries its template, not a catch-all. */
    private void nameRoute(HttpRequest<?> request, String path) {
        String withoutMatrix = path.replaceAll(";[^/]*", "");
        HttpMethod method = request.getMethod();
        router.<Object, Object>find(method, withoutMatrix, request).findFirst()
                .ifPresent(match -> request.setAttribute(HttpAttributes.URI_TEMPLATE, match.getRouteInfo().getUriMatchTemplate().toString()));
    }
}
