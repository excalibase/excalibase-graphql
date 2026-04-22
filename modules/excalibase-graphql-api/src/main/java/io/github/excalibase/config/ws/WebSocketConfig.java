package io.github.excalibase.config.ws;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

/**
 * Routes WebSocket upgrade requests on /graphql to the GraphQLWebSocketHandler,
 * while letting normal POST /graphql pass through to the REST controller.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GraphQLWebSocketHandler graphQLWebSocketHandler;
    private final RealtimeWebSocketHandler realtimeWebSocketHandler;

    public WebSocketConfig(GraphQLWebSocketHandler graphQLWebSocketHandler,
                           RealtimeWebSocketHandler realtimeWebSocketHandler) {
        this.graphQLWebSocketHandler = graphQLWebSocketHandler;
        this.realtimeWebSocketHandler = realtimeWebSocketHandler;
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
                String uri = request.getRequestURI();
                if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade)) return null;
                if ("/graphql".equals(uri)) {
                    return new WebSocketHttpRequestHandler(graphQLWebSocketHandler, new DefaultHandshakeHandler());
                }
                if ("/api/v1/realtime".equals(uri)) {
                    return new WebSocketHttpRequestHandler(realtimeWebSocketHandler, new DefaultHandshakeHandler());
                }
                return null;
            }
        };
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return mapping;
    }
}
