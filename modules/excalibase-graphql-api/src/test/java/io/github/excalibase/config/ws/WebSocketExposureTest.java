package io.github.excalibase.config.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.schema.ExposureSource;
import io.github.excalibase.schema.TableExposure;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.RlsOp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
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
 * Realtime is the one surface that needs an explicit exposure check: CDC events
 * arrive from the database rather than from a field the caller asked for, so
 * nothing on their way out consults the schema the caller was served. Both
 * websocket handlers are covered — the REST-shaped one and the GraphQL one.
 */
class WebSocketExposureTest {

    private static final String PROJECT = "p1";
    private static final JwtClaims CLAIMS =
            JwtClaims.of("u-1", PROJECT, "acme", "demo", "authenticated", "u@x.com");

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

    /** An exposure source that grants SELECT on exactly the named resources. */
    private static RealtimeExposureGate gateGranting(String... resources) {
        ExposureSource source = (orgSlug, projectId, role) -> {
            Map<String, Set<RlsOp>> granted = new HashMap<>();
            for (String resource : resources) {
                granted.put(resource, Set.of(RlsOp.SELECT));
            }
            return TableExposure.enforcing(granted);
        };
        return new RealtimeExposureGate(source);
    }

    private WebSocketSession session(List<String> sink) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-session-" + System.nanoTime());
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            sink.add(msg.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        session.getAttributes().put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, PROJECT);
        session.getAttributes().put(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY, CLAIMS);
        return session;
    }

    private RealtimeWebSocketHandler realtimeHandler(RealtimeExposureGate gate) {
        return new RealtimeWebSocketHandler(subscriptionService, mapper, provider((JwtService) null),
                provider(null), new WebSocketHeartbeat(0), provider(gate));
    }

    private GraphQLWebSocketHandler graphqlHandler(RealtimeExposureGate gate) {
        return new GraphQLWebSocketHandler(subscriptionService, mapper, provider((JwtService) null),
                provider(null), new WebSocketHeartbeat(0), provider(gate));
    }

    @Test
    void handleSubscribe_whenCollectionNotGranted_refusesAsUnknownCollection() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        RealtimeWebSocketHandler handler = realtimeHandler(gateGranting("public.customers"));
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "secrets"))));

        assertThat(sent).hasSize(1);
        assertThat(mapper.readTree(sent.getFirst()).get("type").asText()).isEqualTo("error");
        assertThat(mapper.readTree(sent.getFirst()).get("message").asText()).contains("Unknown collection");
    }

    @Test
    void handleSubscribe_whenCollectionGranted_deliversTheEvent() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        RealtimeWebSocketHandler handler = realtimeHandler(gateGranting("public.customers"));
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "customers"))));
        subscriptionService.publish(null, new CDCEvent(
                "INSERT", "public", "customers", "{\"id\":1}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> !sent.isEmpty());
        assertThat(mapper.readTree(sent.getFirst()).get("type").asText()).isEqualTo("next");
    }

    @Test
    void handleSubscribe_whenGrantCoversAnotherSchemaOnly_refusesTheCollection() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        RealtimeWebSocketHandler handler = realtimeHandler(gateGranting("billing.customers"));
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "customers"))));

        assertThat(mapper.readTree(sent.getFirst()).get("type").asText()).isEqualTo("error");
    }

    @Test
    void graphqlSubscription_whenTableNotGranted_dropsTheEvent() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        GraphQLWebSocketHandler handler = graphqlHandler(gateGranting("public.customers"));
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1",
                "payload", Map.of("query", "subscription { secretsChanges { operation table data } }")))));
        subscriptionService.publish(null, new CDCEvent(
                "INSERT", null, "secrets", "{\"id\":1}", 0L));

        Thread.sleep(300);
        assertThat(sent).noneMatch(msg -> msg.contains("\"next\""));
    }

    @Test
    void graphqlSubscription_whenTableGranted_deliversTheEvent() throws Exception {
        var sent = new ArrayList<String>();
        WebSocketSession session = session(sent);
        GraphQLWebSocketHandler handler = graphqlHandler(gateGranting("public.customers"));
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1",
                "payload", Map.of("query", "subscription { customersChanges { operation table data } }")))));
        subscriptionService.publish(null, new CDCEvent(
                "INSERT", null, "customers", "{\"id\":1}", 0L));

        await().atMost(Duration.ofSeconds(2)).until(() -> sent.stream().anyMatch(msg -> msg.contains("\"next\"")));
    }

    /**
     * The gate asks exposure about the caller's sign-in state, not about the
     * free-form {@code role} claim: that claim drives row-level policies and
     * defaults to "user", which matches neither role a grant may name.
     */
    @Test
    void permitsRead_whenSessionIsAuthenticated_asksExposureForTheAuthenticatedRole() throws Exception {
        var asked = new ArrayList<String>();
        ExposureSource source = (orgSlug, projectId, role) -> {
            asked.add(role);
            return TableExposure.UNRESTRICTED;
        };
        WebSocketSession session = session(new ArrayList<>());
        session.getAttributes().put(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY,
                JwtClaims.of("u-1", PROJECT, "acme", "demo", "user", "u@x.com"));

        new RealtimeExposureGate(source).permitsRead(session, "public.customers");

        assertThat(asked).containsExactly("authenticated");
    }

    @Test
    void permitsRead_whenSessionHasNoClaims_asksExposureForTheAnonRole() throws Exception {
        var asked = new ArrayList<String>();
        ExposureSource source = (orgSlug, projectId, role) -> {
            asked.add(role);
            return TableExposure.UNRESTRICTED;
        };
        WebSocketSession session = session(new ArrayList<>());
        session.getAttributes().remove(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY);

        new RealtimeExposureGate(source).permitsRead(session, "public.customers");

        assertThat(asked).containsExactly("anon");
    }

    @Test
    void permitsRead_whenSessionHasNoProject_passesThrough() throws Exception {
        WebSocketSession session = session(new ArrayList<>());
        session.getAttributes().remove(GraphQLWebSocketHandler.SESSION_PROJECT_KEY);

        assertThat(gateGranting().permitsRead(session, "public.anything")).isTrue();
    }
}
