package io.github.excalibase.config.ws;

import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.JwtVerificationException;
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
 * Verifies the JWT on the WebSocket HTTP upgrade when an {@code Authorization: Bearer}
 * header is present. Successful verification stashes the tenant claim on the session
 * attributes under {@link GraphQLWebSocketHandler#SESSION_TENANT_KEY}, short-circuiting
 * the {@code connection_init} payload auth later.
 *
 * <p>Design: <strong>fail-closed on invalid headers, fail-through on missing ones.</strong>
 * Browser-based GraphQL clients (Apollo, urql, graphql-ws) cannot set headers on the WS
 * upgrade — they send the JWT in {@code connection_init.payload.Authorization} — so this
 * interceptor does NOT reject requests without an Authorization header. Both paths are
 * fail-closed in their own layer:
 * <ul>
 *     <li>With header (server-to-server, CLI): rejected here, no WS established.</li>
 *     <li>Without header (browsers): handled in {@code GraphQLWebSocketHandler.handleConnectionInit}.</li>
 * </ul>
 */
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER)) {
            // No header → let connection_init auth take over.
            return true;
        }
        String token = authHeader.substring(BEARER.length()).trim();
        try {
            JwtClaims claims = jwtService.verify(token);
            String tenantId = GraphQLWebSocketHandler.tenantIdFromClaims(claims);
            if (tenantId == null) {
                reject(response, "JWT missing projectId claim");
                return false;
            }
            attributes.put(GraphQLWebSocketHandler.SESSION_TENANT_KEY, tenantId);
            attributes.put(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY, claims);
            log.info("WS handshake authenticated via Authorization header for tenant '{}'", tenantId);
            return true;
        } catch (JwtVerificationException e) {
            reject(response, "Invalid or expired token");
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private void reject(ServerHttpResponse response, String reason) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        if (response instanceof ServletServerHttpResponse servlet) {
            servlet.getServletResponse().setHeader("X-Auth-Reason", reason);
        }
        log.warn("Rejected WS handshake: {}", reason);
    }
}
