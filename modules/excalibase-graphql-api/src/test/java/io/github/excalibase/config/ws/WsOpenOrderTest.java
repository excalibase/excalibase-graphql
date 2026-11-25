package io.github.excalibase.config.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;

import io.micronaut.http.HttpRequest;
import io.micronaut.websocket.WebSocketSession;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The upgrade is completed on a request thread and the client's first frame is read on the event loop, so the
 * message handler can run before the open handler. A frame is handled only after the session is open.
 */
class WsOpenOrderTest {

    private static WebSocketSession session() {
        Map<String, Object> store = new ConcurrentHashMap<>();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.put(anyString(), any())).thenAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        });
        when(session.get(anyString(), any(Class.class))).thenAnswer(invocation ->
                Optional.ofNullable(store.get((String) invocation.getArgument(0))));
        return session;
    }

    @Test
    @DisplayName("a GraphQL frame that arrives before the open handler has run waits for it")
    void graphqlMessageWaitsForOpen() throws Exception {
        GraphQLWebSocketHandler handler = mock(GraphQLWebSocketHandler.class);
        GraphqlWsEndpoint endpoint = new GraphqlWsEndpoint(handler);
        WebSocketSession session = session();
        HttpRequest<?> request = HttpRequest.GET("/p1/graphql");
        CountDownLatch messageStarted = new CountDownLatch(1);
        try (ExecutorService other = Executors.newSingleThreadExecutor()) {
            other.submit(() -> {
                messageStarted.countDown();
                endpoint.onMessage("{\"type\":\"connection_init\"}", session);
                return null;
            });
            messageStarted.await();
            Thread.sleep(200);   // the frame is being handled while the open handler has not run

            endpoint.onOpen(session, request);

            InOrder order = inOrder(handler);
            order.verify(handler).afterConnectionEstablished(any());
            order.verify(handler, timeout(5_000)).onMessage(any(), eq("{\"type\":\"connection_init\"}"));
        }
    }

    @Test
    @DisplayName("a realtime frame that arrives before the open handler has run waits for it")
    void realtimeMessageWaitsForOpen() throws Exception {
        RealtimeWebSocketHandler handler = mock(RealtimeWebSocketHandler.class);
        RealtimeWsEndpoint endpoint = new RealtimeWsEndpoint(handler);
        WebSocketSession session = session();
        HttpRequest<?> request = HttpRequest.GET("/p1/api/v1/realtime");
        CountDownLatch messageStarted = new CountDownLatch(1);
        try (ExecutorService other = Executors.newSingleThreadExecutor()) {
            other.submit(() -> {
                messageStarted.countDown();
                endpoint.onMessage("{\"action\":\"subscribe\"}", session);
                return null;
            });
            messageStarted.await();
            Thread.sleep(200);

            endpoint.onOpen(session, request);

            InOrder order = inOrder(handler);
            order.verify(handler).afterConnectionEstablished(any());
            order.verify(handler, timeout(5_000)).onMessage(any(), eq("{\"action\":\"subscribe\"}"));
        }
    }
}
