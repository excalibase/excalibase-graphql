package io.github.excalibase.config.ws;

import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.RoleNotAllowedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Authenticates the WebSocket HTTP upgrade from its headers: {@code Authorization: Bearer}
 * and {@code X-Excalibase-Role}. The session's {@link io.github.excalibase.security.Principal}
 * is stored under {@link GraphQLWebSocketHandler#SESSION_PRINCIPAL_KEY}.
 *
 * <p>Fail-closed on invalid headers, fail-through on a missing token: browser clients
 * cannot set headers on the upgrade and send the token (and role) in the
 * {@code connection_init} payload instead, where the handlers resolve the session again.
 */
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);

    private final JwtService jwtService;

    public JwtHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        HttpHeaders headers = request.getHeaders();
        // The project is in the URL path (authoritative); a token for another project
        // cannot open this stream, and its audience has to cover this one (EXC-11).
        String pathProject = WsProjectPath.projectId(request.getURI().getPath());
        try {
            WsSessionAuth.onHandshake(jwtService, headers.getFirst(HttpHeaders.AUTHORIZATION),
                    WsSessionAuth.excalibaseHeaders(headers.toSingleValueMap()), pathProject, attributes);
            return true;
        } catch (WsSessionAuth.Failure failure) {
            reject(response, failure);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private void reject(ServerHttpResponse response, WsSessionAuth.Failure failure) {
        boolean forbidden = RoleNotAllowedException.ROLE_NOT_ALLOWED.equals(failure.code());
        response.setStatusCode(forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED);
        if (response instanceof ServletServerHttpResponse servlet) {
            servlet.getServletResponse().setHeader("X-Auth-Reason", failure.getMessage());
        }
        log.warn("Rejected WS handshake: {}", failure.getMessage());
    }
}
