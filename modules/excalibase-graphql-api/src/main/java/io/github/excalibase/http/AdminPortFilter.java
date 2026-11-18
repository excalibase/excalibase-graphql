package io.github.excalibase.http;

import io.github.excalibase.admin.AdminGuard;
import io.github.excalibase.admin.AdminSettings;
import io.github.excalibase.config.ws.ClientAddress;
import io.github.excalibase.security.KnownProjectFilter;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

import java.net.InetSocketAddress;

/**
 * Keeps the admin API on the admin port and nothing else on it. {@code /_admin/**} is a 404 on every other port, and
 * with the console off on all of them; the admin port serves {@code /_admin/**} only, to a caller holding the secret.
 * A request let through is marked, so the project, token and CORS filters leave it alone: it names no project.
 * The address that counts failures is the TCP peer, never a forwarded header, so a client cannot pick its own.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class AdminPortFilter implements Ordered {

    /** Behind the security headers (-950), so every answer here carries them, ahead of every filter that reads a project. */
    public static final int ORDER = -945;

    private static final String ADMIN = "/_admin";
    private static final String UNAUTHORIZED = "{\"error\":\"unauthorized\"}";
    private static final String TOO_MANY = "{\"error\":\"too_many_failed_attempts\"}";

    private final AdminSettings settings;
    private final AdminGuard guard;

    public AdminPortFilter(AdminSettings settings, @Nullable AdminGuard guard) {
        this.settings = settings;
        this.guard = guard;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        String target = RequestTarget.rawPath(request);
        if (!onAdminPort(request)) {
            return isAdmin(target) || isAdmin(request.getPath()) ? DefaultErrorBody.response(request, HttpStatus.NOT_FOUND) : null;
        }
        if (!isAdmin(target)) {
            return DefaultErrorBody.response(request, HttpStatus.NOT_FOUND);
        }
        return authorize(request);
    }

    private HttpResponse<?> authorize(HttpRequest<?> request) {
        String address = peer(request);
        switch (guard.check(address, request.getHeaders().get(AdminGuard.SECRET_HEADER))) {
            case ALLOWED -> {
                request.getAttributes().put(KnownProjectFilter.ADMIN_ATTR, Boolean.TRUE);
                return null;
            }
            case LIMITED -> {
                return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", Long.toString(guard.retryAfterSeconds(address)))
                        .contentType(MediaType.APPLICATION_JSON_TYPE).body(TOO_MANY);
            }
            default -> {
                return HttpResponse.status(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON_TYPE).body(UNAUTHORIZED);
            }
        }
    }

    private boolean onAdminPort(HttpRequest<?> request) {
        InetSocketAddress server = request.getServerAddress();
        return settings.enabled() && guard != null && server != null && server.getPort() == settings.port();
    }

    private static boolean isAdmin(String path) {
        return path != null && (path.equals(ADMIN) || path.startsWith(ADMIN + "/"));
    }

    private static String peer(HttpRequest<?> request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown"
                : ClientAddress.byPrefix(remote.getAddress().getHostAddress());
    }
}
