package io.github.excalibase.config.ws;

import io.github.excalibase.cors.CorsFilter;
import io.github.excalibase.cors.CorsRules;
import io.github.excalibase.cors.ProjectCorsConfigurationSource;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.RoleNotAllowedException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admits a WebSocket upgrade: the project's CORS allowlist is checked against {@code Origin} (browsers apply no
 * CORS to WebSockets), the path project is captured as authoritative for RLS, and when authentication is on the
 * upgrade's {@code Authorization} and role headers are verified. A missing token falls through to the
 * {@code connection_init} payload, where the handlers resolve the session again.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class WsHandshakeFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(WsHandshakeFilter.class);
    static final String ATTRIBUTES = "excalibase.ws.attributes";
    private static final String NULL_ORIGIN = "null";

    private final ProjectCorsConfigurationSource corsSource;
    private final JwtService jwtService;

    public WsHandshakeFilter(ProjectCorsConfigurationSource corsSource, @Nullable JwtService jwtService) {
        this.corsSource = corsSource;
        this.jwtService = jwtService;
    }

    @Override
    public int getOrder() {
        return -70;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        String upgrade = request.getHeaders().get("Upgrade");
        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade)) {
            return null;
        }
        String pathProject = WsProjectPath.projectId(request.getPath());
        if (pathProject == null) {
            return null;
        }
        String origin = request.getHeaders().get("Origin");
        if (origin != null && !origin.isBlank() && isWebOrigin(origin) && !webOriginAllowed(request, origin)) {
            log.warn("Rejected WS handshake: origin not on the project's allowlist");
            return HttpResponse.status(HttpStatus.FORBIDDEN);
        }
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, pathProject);
        request.getAttributes().put(ATTRIBUTES, attributes);
        if (jwtService == null) {
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        request.getHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        try {
            Map<String, Object> established = new HashMap<>();
            WsSessionAuth.onHandshake(jwtService, request.getHeaders().get("Authorization"),
                    WsSessionAuth.excalibaseHeaders(headers), pathProject, established);
            attributes.putAll(established);
            return null;
        } catch (WsSessionAuth.Failure failure) {
            boolean forbidden = RoleNotAllowedException.ROLE_NOT_ALLOWED.equals(failure.code());
            log.warn("Rejected WS handshake: {}", failure.getMessage());
            return HttpResponse.status(forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED)
                    .header("X-Auth-Reason", failure.getMessage());
        }
    }

    /** True for an http(s) origin and for anything unparseable, which is checked (and refused) as one. */
    private static boolean isWebOrigin(String origin) {
        if (NULL_ORIGIN.equals(origin)) {
            return false;
        }
        int separator = origin.indexOf("://");
        if (separator <= 0) {
            return true;
        }
        String scheme = origin.substring(0, separator).toLowerCase(Locale.ROOT);
        return scheme.equals("http") || scheme.equals("https");
    }

    private boolean webOriginAllowed(HttpRequest<?> request, String origin) {
        if (CorsFilter.sameOrigin(request, origin)) {
            return true;
        }
        CorsRules rules = corsSource.getCorsConfiguration(request);
        return rules != null && rules.checkOrigin(origin) != null;
    }
}
