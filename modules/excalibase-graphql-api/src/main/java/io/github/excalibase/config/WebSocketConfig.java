package io.github.excalibase.config;

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

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final GraphQLWebSocketHandler graphQLWebSocketHandler;

    public WebSocketConfig(GraphQLWebSocketHandler graphQLWebSocketHandler) {
        this.graphQLWebSocketHandler = graphQLWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Intentionally left blank. We bind WS on /graphql via a custom HandlerMapping that
        // only activates for HTTP Upgrade requests, allowing POST /graphql to be handled by MVC.
    }

    @Bean
    public HandlerMapping graphqlWebSocketUpgradeMapping() {
        AbstractHandlerMapping mapping = new AbstractHandlerMapping() {
            @Override
            protected Object getHandlerInternal(HttpServletRequest request) {
                String upgrade = request.getHeader("Upgrade");
                String uri = request.getRequestURI();
                if ("/graphql".equals(uri) && upgrade != null && "websocket".equalsIgnoreCase(upgrade)) {
                    return new WebSocketHttpRequestHandler(graphQLWebSocketHandler, new DefaultHandshakeHandler());
                }
                return null; // Defer to other handler mappings (e.g., @PostMapping "/graphql")
            }
        };
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return mapping;
    }
}


