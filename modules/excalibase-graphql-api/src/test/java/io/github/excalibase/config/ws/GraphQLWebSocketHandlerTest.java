package io.github.excalibase.config.ws;

import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * What the GraphQL-over-WebSocket handler delivers per subscriber (audit C2): only tables the role can
 * select, only rows its select filter passes, only its select columns — decided by its access plan,
 * as on the query and REST paths.
 */
class GraphQLWebSocketHandlerTest {

    private static final String ROLE = "app_authenticated";
    private static final String OWNED = "{\"owner_id\":{\"_eq\":\"X-Excalibase-User-Id\"}}";

    private SubscriptionService subscriptionService;
    private ObjectMapper mapper;
    private final List<String> probes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        subscriptionService = new SubscriptionService();
        mapper = new ObjectMapper();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    private static SchemaInfo schema() {
        SchemaInfo schema = new SchemaInfo();
        for (String table : List.of("public.notes", "public.things", "public.orgs")) {
            schema.setTableSchema(table, "public");
            schema.addColumn(table, "id", "integer");
            schema.addPrimaryKey(table, "id");
        }
        schema.addColumn("public.notes", "owner_id", "text");
        schema.addColumn("public.notes", "secret", "text");
        schema.addColumn("public.notes", "org_id", "integer");
        schema.addColumn("public.things", "secret", "text");
        schema.addColumn("public.things", "name", "text");
        schema.addColumn("public.orgs", "name", "text");
        schema.addForeignKey("public.notes", "org_id", "public.orgs", "id");
        return schema;
    }

    private static String entry(String table, String select) {
        return "{\"table\":\"" + table + "\",\"role\":\"" + ROLE + "\",\"select\":" + select + "}";
    }

    private GraphQLWebSocketHandler handler(String... tableEntries) {
        AccessPlans plans = StubbedSchemaManager.withDocument(schema(),
                StubbedSchemaManager.document("p1", tableEntries), (sql, params) -> probes.add(sql));
        return new GraphQLWebSocketHandler(subscriptionService, mapper,
                provider((JwtService) null), new WebSocketHeartbeat(0), provider(plans));
    }

    private WebSocketSession session(List<String> sink, String projectId, JwtClaims claims) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("gql-session-" + System.nanoTime());
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        if (projectId != null) {
            attrs.put(GraphQLWebSocketHandler.SESSION_TENANT_KEY, projectId);
            // The handshake interceptor sets the path project; the plan is that project's.
            attrs.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, projectId);
        }
        if (claims != null) {
            attrs.put(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY, claims);
            attrs.put(GraphQLWebSocketHandler.SESSION_PRINCIPAL_KEY, principalOf(claims));
        }
        when(session.getAttributes()).thenReturn(attrs);
        doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            sink.add(msg.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    private static JwtClaims user(String userId) {
        return new JwtClaims(userId, "p1", "acme", "demo", "", ROLE, "u@x.com", "authenticated", 0L,
                Map.of("userId", userId), null);
    }

    private static Principal principalOf(JwtClaims claims) {
        return Principal.SERVICE.equals(claims.role())
                ? new Principal(Principal.SERVICE, true, claims, Map.of())
                : new Principal(claims.role(), false, claims, Map.of());
    }

    private void subscribe(GraphQLWebSocketHandler handler, WebSocketSession session, String field) throws Exception {
        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1",
                "payload", Map.of("query", "subscription { " + field + " { operation table data timestamp } }")))));
    }

    /** The "data" object embedded in a graphql-transport-ws "next" message. */
    private JsonNode deliveredData(String raw) throws Exception {
        // payload.data.<field>.data
        JsonNode fieldNode = mapper.readTree(raw).get("payload").get("data").elements().next();
        return fieldNode.get("data");
    }

    @Test
    @DisplayName("the select filter delivers only the subscriber's rows over GraphQL WS")
    void rowFilter_deliversOnlyOwnRows() throws Exception {
        var handler = handler(entry("public.notes", "{\"filter\":" + OWNED + ",\"columns\":\"*\"}"));
        var sent = new ArrayList<String>();
        var session = session(sent, "p1", user("u-1"));
        handler.afterConnectionEstablished(session);
        subscribe(handler, session, "notesChanges");

        subscriptionService.publish("p1", new CDCEvent(
                "INSERT", null, "notes", "{\"id\":1,\"owner_id\":\"u-2\"}", 0L));
        subscriptionService.publish("p1", new CDCEvent(
                "INSERT", null, "notes", "{\"id\":2,\"owner_id\":\"u-1\"}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        assertThat(deliveredData(sent.getFirst()).get("id").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("only the select columns reach the GraphQL WS payload")
    void columns_onlyTheSelectColumnsAreDelivered() throws Exception {
        var handler = handler(entry("public.things", "{\"filter\":{},\"columns\":[\"id\",\"name\"]}"));
        var sent = new ArrayList<String>();
        var session = session(sent, "p1", user("u-1"));
        handler.afterConnectionEstablished(session);
        subscribe(handler, session, "thingsChanges");

        subscriptionService.publish("p1", new CDCEvent(
                "INSERT", null, "things", "{\"id\":1,\"secret\":\"x\",\"name\":\"n\"}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        JsonNode data = deliveredData(sent.getFirst());
        assertThat(data.has("secret")).isFalse();
        assertThat(data.get("name").asText()).isEqualTo("n");
        assertThat(data.get("id").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a table the role cannot select is refused at subscribe")
    void unselectableTable_isRefused() throws Exception {
        var handler = handler(entry("public.notes", "{\"filter\":{},\"columns\":\"*\"}"));
        var sent = new ArrayList<String>();
        var session = session(sent, "p1", user("u-1"));
        handler.afterConnectionEstablished(session);
        subscribe(handler, session, "thingsChanges");

        subscriptionService.publish("p1", new CDCEvent("INSERT", null, "things", "{\"id\":1}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        assertThat(mapper.readTree(sent.getFirst()).get("type").asText()).isEqualTo("error");
    }

    @Test
    @DisplayName("a service session sees every row and column")
    void serviceSession_seesEverything() throws Exception {
        var handler = handler(entry("public.things", "{\"filter\":{},\"columns\":[\"id\"]}"));
        var sent = new ArrayList<String>();
        var service = new JwtClaims("svc", "p1", "acme", "demo", "", "service", "apikey:1", "service", 1L);
        var session = session(sent, "p1", service);
        handler.afterConnectionEstablished(session);
        subscribe(handler, session, "thingsChanges");

        subscriptionService.publish("p1", new CDCEvent(
                "INSERT", null, "things", "{\"id\":1,\"owner_id\":\"someone\",\"secret\":\"x\"}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(deliveredData(sent.getFirst()).get("secret").asText()).isEqualTo("x");
    }

    @Test
    @DisplayName("an UPDATE is judged on its old and new images, and each image is projected")
    void update_judgedPerImageAndProjected() throws Exception {
        var handler = handler(entry("public.notes",
                "{\"filter\":" + OWNED + ",\"columns\":[\"id\",\"owner_id\"]}"));
        var sent = new ArrayList<String>();
        var session = session(sent, "p1", user("u-1"));
        handler.afterConnectionEstablished(session);
        subscribe(handler, session, "notesChanges");

        subscriptionService.publish("p1", new CDCEvent("UPDATE", null, "notes",
                "{\"old\":{\"id\":1,\"owner_id\":\"u-2\",\"secret\":\"x\"},"
                        + "\"new\":{\"id\":1,\"owner_id\":\"u-2\",\"secret\":\"y\"}}", 0L));
        subscriptionService.publish("p1", new CDCEvent("UPDATE", null, "notes",
                "{\"old\":{\"id\":2,\"owner_id\":\"u-1\",\"secret\":\"x\"},"
                        + "\"new\":{\"id\":2,\"owner_id\":\"u-1\",\"secret\":\"y\"}}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        JsonNode data = deliveredData(sent.getFirst());
        assertThat(data.get("new").get("id").asInt()).isEqualTo(2);
        assertThat(data.get("new").has("secret")).isFalse();
        assertThat(data.get("old").has("secret")).isFalse();
    }

    @Test
    @DisplayName("a relationship filter asks the database for a row that exists, never for a deleted one")
    void relationshipFilter_probesForExistingRowsOnly() throws Exception {
        var handler = handler(entry("public.notes",
                "{\"filter\":{\"publicOrgId\":{\"name\":{\"_eq\":\"acme\"}}},\"columns\":\"*\"}"),
                entry("public.orgs", "{\"filter\":{},\"columns\":\"*\"}"));
        var sent = new ArrayList<String>();
        var session = session(sent, "p1", user("u-1"));
        handler.afterConnectionEstablished(session);
        subscribe(handler, session, "notesChanges");

        subscriptionService.publish("p1", new CDCEvent("DELETE", null, "notes", "{\"id\":3,\"org_id\":1}", 0L));
        subscriptionService.publish("p1", new CDCEvent("INSERT", null, "notes", "{\"id\":4,\"org_id\":1}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        assertThat(deliveredData(sent.getFirst()).get("id").asInt()).isEqualTo(4);
        assertThat(probes).hasSize(1);
    }

    @Test
    @DisplayName("a session without a project passes events through (single-tenant)")
    void noProject_passthrough() throws Exception {
        var handler = handler();
        var sent = new ArrayList<String>();
        var session = session(sent, null, null);
        handler.afterConnectionEstablished(session);
        subscribe(handler, session, "widgetChanges");

        subscriptionService.publish(null, new CDCEvent(
                "INSERT", null, "widget", "{\"id\":7}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(deliveredData(sent.getFirst()).get("id").asInt()).isEqualTo(7);
    }
}
