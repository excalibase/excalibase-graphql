package io.github.excalibase.config.ws;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Captures the {@code projectId} from the project-scoped WebSocket upgrade path
 * ({@code /{projectId}/graphql} or {@code /{projectId}/api/v1/realtime}) and
 * stashes it on the session under {@link GraphQLWebSocketHandler#SESSION_PROJECT_KEY}.
 * That value is authoritative for RLS on every CDC event, mirroring how the HTTP
 * {@code JwtAuthFilter} reads the project from the URL path — the project is a
 * property of the resource, not of the token.
 */
class ProjectPathHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String projectId = WsProjectPath.projectId(request.getURI().getPath());
        if (projectId != null) {
            attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, projectId);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
