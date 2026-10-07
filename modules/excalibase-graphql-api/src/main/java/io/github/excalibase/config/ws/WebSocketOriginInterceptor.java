package io.github.excalibase.config.ws;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Locale;
import java.util.Map;

/**
 * The project's CORS allowlist, applied to the WebSocket upgrade. Browsers do
 * not apply CORS to WebSockets, so the server checks {@code Origin} itself:
 * <ul>
 *   <li>no {@code Origin} — a server-side client — passes;</li>
 *   <li>a web page ({@code http}/{@code https}) passes when it is same-origin or
 *       on the project's allowlist, and is otherwise refused with 403;</li>
 *   <li>any other scheme ({@code capacitor://}, {@code tauri://}, {@code app://},
 *       {@code file://}) and {@code null} come from native apps or local files,
 *       not from a site an attacker can send a visitor to, and pass.</li>
 * </ul>
 * The session is still authenticated by its bearer token; this only keeps an
 * unlisted web page from opening the stream, as CORS does for HTTP.
 */
class WebSocketOriginInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketOriginInterceptor.class);

    private static final String NULL_ORIGIN = "null";

    private final CorsConfigurationSource corsSource;

    WebSocketOriginInterceptor(CorsConfigurationSource corsSource) {
        this.corsSource = corsSource;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String origin = request.getHeaders().getFirst(HttpHeaders.ORIGIN);
        if (origin == null || origin.isBlank() || !isWebOrigin(origin) || webOriginAllowed(request, origin)) {
            return true;
        }
        response.setStatusCode(HttpStatus.FORBIDDEN);
        log.warn("Rejected WS handshake: origin not on the project's allowlist");
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
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

    private boolean webOriginAllowed(ServerHttpRequest request, String origin) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        HttpServletRequest servlet = servletRequest.getServletRequest();
        try {
            if (!CorsUtils.isCorsRequest(servlet)) {
                return true;
            }
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        CorsConfiguration config = corsSource.getCorsConfiguration(servlet);
        return config != null && config.checkOrigin(origin) != null;
    }
}
