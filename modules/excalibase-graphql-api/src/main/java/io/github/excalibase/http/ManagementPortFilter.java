package io.github.excalibase.http;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

/**
 * The management port serves the actuator and nothing else: the framework mounts every route on it, so a project's
 * GraphQL, REST and WebSocket paths are answered with the default 404 there, as the separate management context
 * answered them.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class ManagementPortFilter implements Ordered {

    /** Behind the security headers (-950), so the 404 carries them, ahead of every filter that reads a project. */
    public static final int ORDER = -940;

    private static final String ACTUATOR = "/actuator";

    private final int managementPort;

    public ManagementPortFilter(@Value("${endpoints.all.port:-1}") int managementPort) {
        this.managementPort = managementPort;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        if (managementPort <= 0 || request.getServerAddress().getPort() != managementPort) {
            return null;
        }
        String path = RequestTarget.rawPath(request);
        if (path.equals(ACTUATOR) || path.startsWith(ACTUATOR + "/")) {
            return null;
        }
        return DefaultErrorBody.response(request, HttpStatus.NOT_FOUND);
    }
}
