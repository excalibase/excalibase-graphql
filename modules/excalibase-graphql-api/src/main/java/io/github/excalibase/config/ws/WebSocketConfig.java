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

import java.util.List;

/**
 * Routes WebSocket upgrade requests:
 * <ul>
 *     <li>{@code /graphql} → {@link GraphQLWebSocketHandler} (CDC subscriptions)</li>
 *     <li>{@code /api/v1/realtime} → {@link RealtimeWebSocketHandler} (REST CDC stream)</li>
 * </ul>
 * Normal POST /graphql passes through to the REST controller (not an Upgrade request).
 *
 * <p>When {@code JwtService} is available (i.e. {@code app.security.jwt-enabled=true}),
 * a {@link JwtHandshakeInterceptor} is installed on <strong>both</strong> routes for
 * defense-in-depth. Server-to-server clients that send {@code Authorization: Bearer}
 * on the upgrade are fail-closed at the HTTP layer before a WebSocket is even
 * established. Browser clients that can't set headers fall through to the
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
                if ("/graphql".equals(uri)) {
                    return buildHandler(graphQLWebSocketHandler);
                }
                if ("/api/v1/realtime".equals(uri)) {
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
        if (jwtService != null) {
            handler.setHandshakeInterceptors(
                    List.<HandshakeInterceptor>of(new JwtHandshakeInterceptor(jwtService)));
        }
        return handler;
    }
}
