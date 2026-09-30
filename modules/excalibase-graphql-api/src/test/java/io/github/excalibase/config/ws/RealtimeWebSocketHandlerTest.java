package io.github.excalibase.config.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.StubbedSchemaManager;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
        handler = new RealtimeWebSocketHandler(subscriptionService, mapper, provider((JwtService) null),
                new WebSocketHeartbeat(0), provider((AccessPlans) null));
    }

    private WebSocketSession session(List<String> sink) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-session-" + System.nanoTime());
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
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

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    private static final String ROLE = "app_authenticated";

    /** notes and things for {@code ROLE}, per the given select permissions. */
    private RealtimeWebSocketHandler planned(String notesSelect, String thingsSelect) {
        SchemaInfo schema = new SchemaInfo();
        for (String table : List.of("public.notes", "public.things")) {
            schema.setTableSchema(table, "public");
            schema.addColumn(table, "id", "integer");
            schema.addColumn(table, "secret", "text");
            schema.addPrimaryKey(table, "id");
        }
        schema.addColumn("public.notes", "owner_id", "text");
        schema.addColumn("public.things", "name", "text");
        String document = StubbedSchemaManager.document("p1",
                "{\"table\":\"public.notes\",\"role\":\"" + ROLE + "\",\"select\":" + notesSelect + "}",
                "{\"table\":\"public.things\",\"role\":\"" + ROLE + "\",\"select\":" + thingsSelect + "}");
        AccessPlans plans = StubbedSchemaManager.withDocument(schema, document, (sql, params) -> false);
        return new RealtimeWebSocketHandler(subscriptionService, mapper, provider((JwtService) null),
                new WebSocketHeartbeat(0), provider(plans));
    }

    private static final String OWNED_NOTES = "{\"filter\":{\"owner_id\":{\"_eq\":\"X-Excalibase-User-Id\"}},"
            + "\"columns\":[\"id\",\"owner_id\"]}";
    private static final String THING_NAMES = "{\"filter\":{},\"columns\":[\"id\",\"name\"]}";

    private WebSocketSession userSession(List<String> sink) throws Exception {
        WebSocketSession session = session(sink);
        session.getAttributes().put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, "p1");
        JwtClaims claims = new JwtClaims("u-1", "p1", "acme", "demo", "", ROLE, "u@x.com", "authenticated", 0L,
                Map.of("userId", "u-1"), null);
        session.getAttributes().put(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY, claims);
        session.getAttributes().put(GraphQLWebSocketHandler.SESSION_PRINCIPAL_KEY,
                new Principal(claims.role(), false, claims, Map.of()));
        return session;
    }

    @Test
    @DisplayName("only the select columns reach the realtime payload")
    void columns_onlyTheSelectColumnsAreDelivered() throws Exception {
        var planned = planned(OWNED_NOTES, THING_NAMES);
        var sent = new ArrayList<String>();
        WebSocketSession session = userSession(sent);
        planned.afterConnectionEstablished(session);

        planned.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "things"))));

        subscriptionService.publish(null, new CDCEvent(
                "INSERT", "public", "things", "{\"id\":1,\"secret\":\"x\",\"name\":\"n\"}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        var doc = mapper.readTree(sent.getFirst()).get("doc");
        assertThat(doc.has("secret")).isFalse();
        assertThat(doc.get("name").asText()).isEqualTo("n");
        assertThat(doc.get("id").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("an UPDATE is judged on its old and new images, and each image is projected")
    void update_judgedPerImageAndProjected() throws Exception {
        var planned = planned(OWNED_NOTES, THING_NAMES);
        var sent = new ArrayList<String>();
        WebSocketSession session = userSession(sent);
        planned.afterConnectionEstablished(session);
        planned.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "notes"))));

        subscriptionService.publish(null, new CDCEvent("UPDATE", "public", "notes",
                "{\"old\":{\"id\":1,\"owner_id\":\"u-2\",\"secret\":\"x\"},"
                        + "\"new\":{\"id\":1,\"owner_id\":\"u-2\",\"secret\":\"y\"}}", 0L));
        subscriptionService.publish(null, new CDCEvent("UPDATE", "public", "notes",
                "{\"old\":{\"id\":2,\"owner_id\":\"u-1\",\"secret\":\"x\"},"
                        + "\"new\":{\"id\":2,\"owner_id\":\"u-1\",\"secret\":\"y\"}}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        var doc = mapper.readTree(sent.getFirst()).get("doc");
        assertThat(doc.get("new").get("id").asInt()).isEqualTo(2);
        assertThat(doc.get("new").has("secret")).isFalse();
        assertThat(doc.get("old").has("secret")).isFalse();
    }

    @Test
    @DisplayName("the select filter delivers only the subscriber's rows, drops others")
    void rowFilter_deliversOnlyOwnRows() throws Exception {
        var planned = planned(OWNED_NOTES, THING_NAMES);
        var sent = new ArrayList<String>();
        WebSocketSession session = userSession(sent);
        planned.afterConnectionEstablished(session);

        planned.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "notes"))));

        subscriptionService.publish(null, new CDCEvent(
                "INSERT", "public", "notes", "{\"id\":1,\"owner_id\":\"u-2\"}", 0L));
        subscriptionService.publish(null, new CDCEvent(
                "INSERT", "public", "notes", "{\"id\":2,\"owner_id\":\"u-1\"}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        assertThat(mapper.readTree(sent.getFirst()).get("doc").get("id").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("a client filter on a column the role cannot read matches nothing")
    void clientFilterOnHiddenColumn_neverSteersDelivery() throws Exception {
        var planned = planned(OWNED_NOTES, THING_NAMES);
        var sent = new ArrayList<String>();
        WebSocketSession session = userSession(sent);
        planned.afterConnectionEstablished(session);
        planned.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "things", "filter", Map.of("secret", "x")))));

        subscriptionService.publish(null, new CDCEvent(
                "INSERT", "public", "things", "{\"id\":1,\"secret\":\"x\",\"name\":\"n\"}", 0L));

        await().during(Duration.ofMillis(200)).atMost(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(sent).isEmpty());
    }

    @Test
    @DisplayName("a collection the role cannot select is refused as unknown")
    void unselectableCollection_isRefused() throws Exception {
        var planned = planned(OWNED_NOTES, THING_NAMES);
        var sent = new ArrayList<String>();
        WebSocketSession session = userSession(sent);
        planned.afterConnectionEstablished(session);

        planned.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "secrets"))));

        assertThat(sent).hasSize(1);
        assertThat(mapper.readTree(sent.getFirst()).get("message").asText()).contains("Unknown collection");
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

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        subscriptionService.publish(null, new CDCEvent("INSERT", "public", "a", "{}", 0L));
        subscriptionService.publish(null, new CDCEvent("INSERT", "public", "b", "{}", 0L));

        await().during(Duration.ofMillis(200))
                .atMost(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(sent).isEmpty());
    }
}
