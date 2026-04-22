package io.github.excalibase.config.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RealtimeWebSocketHandlerTest {

    private SubscriptionService subscriptionService;
    private RealtimeWebSocketHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        subscriptionService = new SubscriptionService();
        mapper = new ObjectMapper();
        handler = new RealtimeWebSocketHandler(subscriptionService, mapper);
    }

    private WebSocketSession session(List<String> sink) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-session");
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            sink.add(msg.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    @Test
    @DisplayName("nosql INSERT event delivered to matching subscription")
    void nosqlInsert_delivered() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        String subscribeMsg = mapper.writeValueAsString(Map.of(
                "type", "subscribe",
                "id", "s1",
                "source", "nosql",
                "collection", "articles"));
        handler.handleTextMessage(session, new TextMessage(subscribeMsg));

        subscriptionService.publish(new CDCEvent(
                "INSERT", "nosql", "articles",
                "{\"title\":\"hello\"}", 0L));

        await().until(() -> !sent.isEmpty());
        var delivered = mapper.readTree(sent.getFirst());
        assertThat(delivered.get("type").asText()).isEqualTo("next");
        assertThat(delivered.get("id").asText()).isEqualTo("s1");
        assertThat(delivered.get("op").asText()).isEqualTo("insert");
        assertThat(delivered.get("doc").get("title").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("filter rejects non-matching events")
    void filter_rejectsMismatch() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        String subscribeMsg = mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1",
                "source", "nosql", "collection", "orders",
                "filter", Map.of("status", "active")));
        handler.handleTextMessage(session, new TextMessage(subscribeMsg));

        subscriptionService.publish(new CDCEvent(
                "INSERT", "nosql", "orders",
                "{\"status\":\"cancelled\"}", 0L));
        subscriptionService.publish(new CDCEvent(
                "INSERT", "nosql", "orders",
                "{\"status\":\"active\"}", 0L));

        await().until(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        var delivered = mapper.readTree(sent.getFirst());
        assertThat(delivered.get("doc").get("status").asText()).isEqualTo("active");
    }

    @Test
    @DisplayName("rest source routes to public_{collection} key")
    void restSource_usesPublicSchema() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        String subscribeMsg = mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1",
                "source", "rest", "collection", "customers"));
        handler.handleTextMessage(session, new TextMessage(subscribeMsg));

        subscriptionService.publish(new CDCEvent(
                "UPDATE", "public", "customers",
                "{\"name\":\"Vu\"}", 0L));

        await().until(() -> !sent.isEmpty());
        assertThat(mapper.readTree(sent.getFirst()).get("op").asText()).isEqualTo("update");
    }

    @Test
    @DisplayName("DDL and HEARTBEAT events are filtered out")
    void ddlAndHeartbeat_skipped() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        String subscribeMsg = mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1",
                "source", "nosql", "collection", "x"));
        handler.handleTextMessage(session, new TextMessage(subscribeMsg));

        subscriptionService.publish(new CDCEvent("DDL", "nosql", "x", "{}", 0L));
        subscriptionService.publish(new CDCEvent("HEARTBEAT", "nosql", "x", "{}", 0L));
        subscriptionService.publish(new CDCEvent("INSERT", "nosql", "x", "{}", 0L));

        await().until(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        assertThat(mapper.readTree(sent.getFirst()).get("op").asText()).isEqualTo("insert");
    }

    @Test
    @DisplayName("invalid source returns error")
    void invalidSource_error() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        String subscribeMsg = mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1",
                "source", "bogus", "collection", "x"));
        handler.handleTextMessage(session, new TextMessage(subscribeMsg));

        assertThat(sent).hasSize(1);
        var err = mapper.readTree(sent.getFirst());
        assertThat(err.get("type").asText()).isEqualTo("error");
        assertThat(err.get("message").asText()).contains("source must be");
    }

    @Test
    @DisplayName("complete disposes the subscription")
    void complete_disposes() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1",
                "source", "nosql", "collection", "t"))));
        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "complete", "id", "s1"))));

        subscriptionService.publish(new CDCEvent("INSERT", "nosql", "t", "{}", 0L));
        Thread.sleep(100);
        assertThat(sent).isEmpty();
    }
}
