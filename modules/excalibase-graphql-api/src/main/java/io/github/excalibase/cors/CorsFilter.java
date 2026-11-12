package io.github.excalibase.cors;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * CORS where the browser is the enforcer. A preflight from an unlisted origin is refused (403, no CORS headers).
 * An actual request is always served; only a listed origin gets {@code Access-Control-Allow-Origin}, so a
 * browser page from any other origin cannot read the response. Runs ahead of authentication, so a preflight
 * never needs a token, and the per-project origin check covers WebSocket upgrades too.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
@Replaces(io.micronaut.http.server.cors.CorsFilter.class)
public class CorsFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorsFilter.class);
    private static final String RULES_ATTR = "excalibase.corsRules";
    private static final String ORIGIN_ATTR = "excalibase.corsGrantedOrigin";
    public static final int ORDER = -90;

    private final ProjectCorsConfigurationSource source;

    public CorsFilter(ProjectCorsConfigurationSource source) {
        this.source = source;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        String origin = request.getHeaders().get("Origin");
        if (origin == null || sameOrigin(request, origin)) {
            return null;
        }
        CorsRules rules = source.getCorsConfiguration(request);
        if (rules == null) {
            return null;
        }
        if (isPreflight(request)) {
            return preflight(request, origin, rules);
        }
        request.getAttributes().put(RULES_ATTR, rules);
        return null;
    }

    @ResponseFilter
    public void filter(HttpRequest<?> request, MutableHttpResponse<?> response) {
        Object rules = request.getAttribute(RULES_ATTR).orElse(null);
        if (!(rules instanceof CorsRules cors)) {
            return;
        }
        response.getHeaders().add("Vary", "Origin");
        String granted = cors.checkOrigin(request.getHeaders().get("Origin"));
        if (granted == null) {
            return;
        }
        response.header("Access-Control-Allow-Origin", granted);
        if (!cors.exposedHeaders().isEmpty()) {
            response.header("Access-Control-Expose-Headers", String.join(", ", cors.exposedHeaders()));
        }
        if (cors.allowCredentials()) {
            response.header("Access-Control-Allow-Credentials", "true");
        }
    }

    private static boolean isPreflight(HttpRequest<?> request) {
        return request.getMethod() == HttpMethod.OPTIONS
                && request.getHeaders().contains("Access-Control-Request-Method");
    }

    private HttpResponse<?> preflight(HttpRequest<?> request, String origin, CorsRules rules) {
        String allowOrigin = rules.checkOrigin(origin);
        List<String> methods = rules.checkMethod(request.getHeaders().get("Access-Control-Request-Method"));
        String requestedHeaders = request.getHeaders().get("Access-Control-Request-Headers");
        List<String> headers = rules.checkHeaders(requestedHeaders == null ? List.of()
                : Arrays.stream(requestedHeaders.split(",")).map(String::trim).filter(h -> !h.isEmpty()).toList());
        MutableHttpResponse<Object> response = HttpResponse.ok();
        response.header("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
        if (allowOrigin == null || methods == null || headers == null) {
            log.debug("CORS preflight refused");
            return HttpResponse.status(HttpStatus.FORBIDDEN, "Invalid CORS request")
                    .header("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers")
                    .body("Invalid CORS request");
        }
        response.header("Access-Control-Allow-Origin", allowOrigin);
        response.header("Access-Control-Allow-Methods", String.join(", ", methods));
        if (!headers.isEmpty()) {
            response.header("Access-Control-Allow-Headers", String.join(", ", headers));
        }
        if (!rules.exposedHeaders().isEmpty()) {
            response.header("Access-Control-Expose-Headers", String.join(", ", rules.exposedHeaders()));
        }
        if (rules.allowCredentials()) {
            response.header("Access-Control-Allow-Credentials", "true");
        }
        if (rules.maxAgeSeconds() >= 0) {
            response.header("Access-Control-Max-Age", Long.toString(rules.maxAgeSeconds()));
        }
        return response;
    }

    /** True when the Origin is this server's own scheme, host and port. */
    public static boolean sameOrigin(HttpRequest<?> request, String origin) {
        String host = request.getHeaders().get("Host");
        if (host == null) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort() != -1 ? uri.getPort() : defaultPort(scheme);
            String own = request.isSecure() ? "https" : "http";
            URI hostUri = URI.create(own + "://" + host);
            int ownPort = hostUri.getPort() != -1 ? hostUri.getPort() : defaultPort(own);
            return scheme.equals(own) && hostUri.getHost() != null
                    && hostUri.getHost().equalsIgnoreCase(uri.getHost()) && port == ownPort;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) || "wss".equals(scheme) ? 443 : 80;
    }
}
