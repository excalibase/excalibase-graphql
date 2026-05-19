package io.github.excalibase.config.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
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
        org.springframework.beans.factory.ObjectProvider<io.github.excalibase.security.JwtService> noJwt =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override public io.github.excalibase.security.JwtService getObject() { return null; }
                    @Override public io.github.excalibase.security.JwtService getObject(Object... args) { return null; }
                    @Override public io.github.excalibase.security.JwtService getIfAvailable() { return null; }
                    @Override public io.github.excalibase.security.JwtService getIfUnique() { return null; }
                };
        handler = new RealtimeWebSocketHandler(subscriptionService, mapper, noJwt);
    }

    private WebSocketSession session(List<String> sink) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-session-" + System.nanoTime());
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            sink.add(msg.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    @Test
    @DisplayName("INSERT on default schema delivered as {op:insert}")
    void insert_defaultSchema_delivered() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "customers"))));

        subscriptionService.publish(null, new CDCEvent(
                "INSERT", "public", "customers",
                "{\"name\":\"Vu\"}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        var delivered = mapper.readTree(sent.getFirst());
        assertThat(delivered.get("type").asText()).isEqualTo("next");
        assertThat(delivered.get("id").asText()).isEqualTo("s1");
        assertThat(delivered.get("op").asText()).isEqualTo("insert");
        assertThat(delivered.get("doc").get("name").asText()).isEqualTo("Vu");
    }

    @Test
    @DisplayName("UPDATE and DELETE are mapped to lowercase ops")
    void updateAndDelete_mapped() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "orders"))));

        subscriptionService.publish(null, new CDCEvent(
                "UPDATE", "public", "orders", "{\"id\":1}", 0L));
        subscriptionService.publish(null, new CDCEvent(
                "DELETE", "public", "orders", "{\"id\":1}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> sent.size() == 2);
        assertThat(mapper.readTree(sent.get(0)).get("op").asText()).isEqualTo("update");
        assertThat(mapper.readTree(sent.get(1)).get("op").asText()).isEqualTo("delete");
    }

    @Test
    @DisplayName("explicit schema field routes to {schema}_{collection} key")
    void explicitSchema_routes() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1",
                "collection", "items", "schema", "inventory"))));

        // event on public schema must NOT be delivered
        subscriptionService.publish(null, new CDCEvent(
                "INSERT", "public", "items", "{\"sku\":\"A\"}", 0L));
        // event on the requested schema must be delivered
        subscriptionService.publish(null, new CDCEvent(
                "INSERT", "inventory", "items", "{\"sku\":\"B\"}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        assertThat(mapper.readTree(sent.getFirst()).get("doc").get("sku").asText()).isEqualTo("B");
    }

    @Test
    @DisplayName("filter rejects non-matching events, accepts matches")
    void filter_matchesOnlyAcceptedRows() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1",
                "collection", "orders",
                "filter", Map.of("status", "active")))));

        subscriptionService.publish(null, new CDCEvent(
                "INSERT", "public", "orders",
                "{\"status\":\"cancelled\"}", 0L));
        subscriptionService.publish(null, new CDCEvent(
                "INSERT", "public", "orders",
                "{\"status\":\"active\"}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        assertThat(mapper.readTree(sent.getFirst()).get("doc").get("status").asText()).isEqualTo("active");
    }

    @Test
    @DisplayName("DDL and HEARTBEAT events are filtered out")
    void ddlAndHeartbeat_skipped() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "x"))));

        subscriptionService.publish(null, new CDCEvent("DDL", "public", "x", "{}", 0L));
        subscriptionService.publish(null, new CDCEvent("HEARTBEAT", "public", "x", "{}", 0L));
        subscriptionService.publish(null, new CDCEvent("INSERT", "public", "x", "{}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        assertThat(mapper.readTree(sent.getFirst()).get("op").asText()).isEqualTo("insert");
    }

    @Test
    @DisplayName("subscribe without id or collection returns error")
    void missingFields_error() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1"))));

        assertThat(sent).hasSize(1);
        var err = mapper.readTree(sent.getFirst());
        assertThat(err.get("type").asText()).isEqualTo("error");
        assertThat(err.get("message").asText()).contains("id and collection");
    }

    @Test
    @DisplayName("complete disposes the subscription — no further events delivered")
    void complete_disposes() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "t"))));
        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "complete", "id", "s1"))));

        subscriptionService.publish(null, new CDCEvent("INSERT", "public", "t", "{}", 0L));

        await().during(Duration.ofMillis(200))
                .atMost(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(sent).isEmpty());
    }

    @Test
    @DisplayName("afterConnectionClosed disposes all subscriptions on that session")
    void connectionClosed_disposesAllSubs() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "a"))));
        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s2", "collection", "b"))));

        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);

        subscriptionService.publish(null, new CDCEvent("INSERT", "public", "a", "{}", 0L));
        subscriptionService.publish(null, new CDCEvent("INSERT", "public", "b", "{}", 0L));

        await().during(Duration.ofMillis(200))
                .atMost(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(sent).isEmpty());
    }
}
