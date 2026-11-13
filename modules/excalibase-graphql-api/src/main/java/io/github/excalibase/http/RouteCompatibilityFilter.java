package io.github.excalibase.http;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

import java.util.regex.Pattern;

/**
 * Where Spring's route matching and Micronaut's differ: a trailing slash is not the route, and the GraphQL path
 * (also a WebSocket path) serves POST, so a GET that is not an upgrade is a 405 naming it.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class RouteCompatibilityFilter implements Ordered {

    /**
     * Behind every security filter (the last, the WebSocket handshake, is -70): the servlet dispatch that chose a
     * route came after the security chain, so an unknown project or a bad token is answered first.
     */
    public static final int ORDER = -60;

    private static final Pattern GRAPHQL_PATH = Pattern.compile("^/[^/]+/graphql$");

    @Override
    public int getOrder() {
        return ORDER;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        String path = RequestTarget.rawPath(request);
        if (path.length() > 1 && path.endsWith("/")) {
            return DefaultErrorBody.response(request, HttpStatus.NOT_FOUND);
        }
        if (isPlainGet(request) && GRAPHQL_PATH.matcher(path).matches()) {
            return DefaultErrorBody.response(request, HttpStatus.METHOD_NOT_ALLOWED).header("Allow", "POST");
        }
        return null;
    }

    private static boolean isPlainGet(HttpRequest<?> request) {
        return request.getMethod() == HttpMethod.GET
                && !"websocket".equalsIgnoreCase(request.getHeaders().get("Upgrade"));
    }
}
