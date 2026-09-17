package io.github.excalibase.config.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.ColumnPolicy;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.InMemoryPolicyProvider;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.MaskMode;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.RlsPolicyEnforcer;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtService;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the RLS enforcement the GraphQL-over-WebSocket subscription
 * handler applies to CDC events per subscriber (audit C2): row-level visibility
 * and column-level masking, mirroring the query/REST paths.
 */
class GraphQLWebSocketHandlerTest {

    private SubscriptionService subscriptionService;
    private ObjectMapper mapper;

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

    private GraphQLWebSocketHandler handler(RlsPolicyEnforcer enforcer) {
        return new GraphQLWebSocketHandler(subscriptionService, mapper,
                provider((JwtService) null), provider(enforcer), new WebSocketHeartbeat(0), provider((RealtimeExposureGate) null));
    }

    private WebSocketSession session(List<String> sink, String projectId, JwtClaims claims) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("gql-session-" + System.nanoTime());
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        if (projectId != null) {
            attrs.put(GraphQLWebSocketHandler.SESSION_TENANT_KEY, projectId);
            // The handshake interceptor sets the path project; RLS reads it.
            attrs.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, projectId);
        }
        if (claims != null) attrs.put(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY, claims);
        when(session.getAttributes()).thenReturn(attrs);
        doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            sink.add(msg.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
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
    @DisplayName("owner row policy delivers only the subscriber's rows over GraphQL WS")
    void rowFilter_deliversOnlyOwnRows() throws Exception {
        var provider = new InMemoryPolicyProvider();
        provider.put("p1", List.of(new Policy(
                "own", "own", "public.notes", PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("owner_id", FieldType.STRING, RuleOperator.EQ, "{{currentUserId}}")),
                List.of(Assignment.all()))));
        var handler = handler(new RlsPolicyEnforcer(provider));

        var sent = new ArrayList<String>();
        var claims = JwtClaims.of("u-1", "p1", "acme", "demo", "app_authenticated", "u@x.com");
        var session = session(sent, "p1", claims);
        handler.afterConnectionEstablished(session);
        subscribe(handler, session, "notesChanges");

        // schema=null keeps the sink key the bare table (matching the snake table
        // parsed from the query); resourceOf still resolves the policy key public.notes.
        subscriptionService.publish("p1", new CDCEvent(
                "INSERT", null, "notes", "{\"id\":1,\"owner_id\":\"u-2\"}", 0L));
        subscriptionService.publish("p1", new CDCEvent(
                "INSERT", null, "notes", "{\"id\":2,\"owner_id\":\"u-1\"}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        assertThat(deliveredData(sent.getFirst()).get("id").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("column HIDE policy masks the field in the GraphQL WS payload")
    void columnMasking_dropsHiddenField() throws Exception {
        var provider = new InMemoryPolicyProvider();
        provider.putColumns("p1", List.of(new ColumnPolicy(
                "h", "h", "public.things", Set.of("secret"), Operation.ALL, MaskMode.HIDE,
                null, null, 0, true, List.of(Assignment.all()))));
        var handler = handler(new RlsPolicyEnforcer(provider));

        var sent = new ArrayList<String>();
        var claims = JwtClaims.of("u-1", "p1", "acme", "demo", "app_authenticated", "u@x.com");
        var session = session(sent, "p1", claims);
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
    @DisplayName("no RLS engine wired: events pass through unfiltered (single-tenant)")
    void noEngine_passthrough() throws Exception {
        var handler = handler(null);
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
