package io.github.excalibase.config.ws;

import io.github.excalibase.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes WebSocket upgrade requests. Routes are <strong>project-scoped</strong>,
 * matching the HTTP surface — there is no unscoped WS route:
 * <ul>
 *     <li>{@code /{projectId}/graphql} → {@link GraphQLWebSocketHandler} (CDC subscriptions)</li>
 *     <li>{@code /{projectId}/api/v1/realtime} → {@link RealtimeWebSocketHandler} (REST CDC stream)</li>
 * </ul>
 * Normal POST {@code /{projectId}/graphql} passes through to the REST controller
 * (not an Upgrade request).
 *
 * <p>The {@code projectId} from the path is stashed on the session
 * ({@link GraphQLWebSocketHandler#SESSION_PROJECT_KEY}) and is authoritative for
 * RLS — exactly like the HTTP {@code JwtAuthFilter}. When {@code JwtService} is
 * available (i.e. {@code app.security.jwt-enabled=true}), a
 * {@link JwtHandshakeInterceptor} additionally verifies an {@code Authorization:
 * Bearer} header on the upgrade and rejects a token whose project disagrees with
 * the path. Browser clients that can't set headers fall through to the
 * {@code connection_init}-based auth in each handler.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GraphQLWebSocketHandler graphQLWebSocketHandler;
    private final RealtimeWebSocketHandler realtimeWebSocketHandler;
    private final JwtService jwtService;

    public WebSocketConfig(GraphQLWebSocketHandler graphQLWebSocketHandler,
                           RealtimeWebSocketHandler realtimeWebSocketHandler,
                           ObjectProvider<JwtService> jwtServiceProvider) {
        this.graphQLWebSocketHandler = graphQLWebSocketHandler;
        this.realtimeWebSocketHandler = realtimeWebSocketHandler;
        this.jwtService = jwtServiceProvider.getIfAvailable();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Intentionally blank — we bind via custom HandlerMapping below
    }

    @Bean
    public HandlerMapping graphqlWebSocketUpgradeMapping() {
        AbstractHandlerMapping mapping = new AbstractHandlerMapping() {
            @Override
            protected Object getHandlerInternal(HttpServletRequest request) {
                String upgrade = request.getHeader("Upgrade");
                if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade)) return null;
                String uri = request.getRequestURI();
                if (WsProjectPath.projectId(uri, WsProjectPath.GRAPHQL_SUFFIX) != null) {
                    return buildHandler(graphQLWebSocketHandler);
                }
                if (WsProjectPath.projectId(uri, WsProjectPath.REALTIME_SUFFIX) != null) {
                    return buildHandler(realtimeWebSocketHandler);
                }
                return null;
            }
        };
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return mapping;
    }

    private WebSocketHttpRequestHandler buildHandler(WebSocketHandler wsHandler) {
        WebSocketHttpRequestHandler handler = new WebSocketHttpRequestHandler(
                wsHandler, new DefaultHandshakeHandler());
        List<HandshakeInterceptor> interceptors = new ArrayList<>();
        // Path project is authoritative for RLS — always captured, even without JWT.
        interceptors.add(new ProjectPathHandshakeInterceptor());
        if (jwtService != null) {
            interceptors.add(new JwtHandshakeInterceptor(jwtService));
        }
        handler.setHandshakeInterceptors(interceptors);
        return handler;
    }
}
