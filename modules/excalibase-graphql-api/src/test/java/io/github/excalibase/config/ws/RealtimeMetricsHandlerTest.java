package io.github.excalibase.config.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.observability.requests.RequestMetrics;
import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Both realtime handlers count open sessions and delivered changes per project when metrics are on. */
class RealtimeMetricsHandlerTest {

    private static final String PROJECT = "proj-ws";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SubscriptionService subscriptionService = new SubscriptionService();
    private final RequestMetrics metrics = new RequestMetrics(10);

    @Test
    @DisplayName("GraphQL WS: a session counts while open and each delivered change counts once")
    void graphqlHandler() throws Exception {
        GraphQLWebSocketHandler handler = new GraphQLWebSocketHandler(subscriptionService, mapper,
                provider((JwtService) null), new WebSocketHeartbeat(0), provider((AccessPlans) null),
                provider(metrics));
        List<String> sent = new CopyOnWriteArrayList<>();
        WebSocketSession session = session(sent, PROJECT);

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\"}"));
        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of("type", "subscribe",
                "id", "s1", "payload", Map.of("query", "subscription { publicCustomerChanges { operation } }")))));
        subscriptionService.publish(null, new CDCEvent("INSERT", "public", "customer", "{\"id\":1}", 0L));
        await().atMost(Duration.ofSeconds(2)).until(() -> sent.size() == 2);

        assertThat(metrics.scrape())
                .contains("excalibase_realtime_connections{project=\"proj-ws\"} 1")
                .contains("excalibase_realtime_connections_opened_total{project=\"proj-ws\"} 1.0")
                .contains("excalibase_realtime_messages_total{project=\"proj-ws\"} 1.0");

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(metrics.scrape()).contains("excalibase_realtime_connections{project=\"proj-ws\"} 0");
    }

    @Test
    @DisplayName("REST realtime WS: a session counts while open and each delivered change counts once")
    void realtimeHandler() throws Exception {
        RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(subscriptionService, mapper,
                provider((JwtService) null), new WebSocketHeartbeat(0), provider((AccessPlans) null),
                provider(metrics));
        List<String> sent = new CopyOnWriteArrayList<>();
        WebSocketSession session = session(sent, PROJECT);

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "orders"))));
        subscriptionService.publish(null, new CDCEvent("INSERT", "public", "orders", "{\"id\":1}", 0L));
        subscriptionService.publish(null, new CDCEvent("UPDATE", "public", "orders", "{\"id\":1}", 0L));
        await().atMost(Duration.ofSeconds(2)).until(() -> sent.size() == 2);

        assertThat(metrics.scrape())
                .contains("excalibase_realtime_connections{project=\"proj-ws\"} 1")
                .contains("excalibase_realtime_messages_total{project=\"proj-ws\"} 2.0");

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(metrics.scrape()).contains("excalibase_realtime_connections{project=\"proj-ws\"} 0");
    }

    @Test
    @DisplayName("a session without a path project is not counted, and closing it changes nothing")
    void sessionWithoutProject() throws Exception {
        RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(subscriptionService, mapper,
                provider((JwtService) null), new WebSocketHeartbeat(0), provider((AccessPlans) null),
                provider(metrics));
        WebSocketSession session = session(new CopyOnWriteArrayList<>(), null);

        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(metrics.scrape()).doesNotContain("excalibase_realtime_connections{");
    }

    @Test
    @DisplayName("with metrics off the handlers run unchanged")
    void metricsOff() throws Exception {
        TextWebSocketHandler handler = new RealtimeWebSocketHandler(subscriptionService, mapper,
                provider((JwtService) null), new WebSocketHeartbeat(0), provider((AccessPlans) null),
                provider((RequestMetrics) null));
        WebSocketSession session = session(new CopyOnWriteArrayList<>(), PROJECT);

        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(metrics.scrape()).doesNotContain(PROJECT);
    }

    private static WebSocketSession session(List<String> sink, String projectId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("metrics-session-" + System.nanoTime());
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        if (projectId != null) {
            attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, projectId);
        }
        when(session.getAttributes()).thenReturn(attributes);
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            sink.add(message.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }
}
