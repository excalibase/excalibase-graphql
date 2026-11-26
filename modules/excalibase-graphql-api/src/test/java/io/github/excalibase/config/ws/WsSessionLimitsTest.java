package io.github.excalibase.config.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.compiler.ParsedQueries;
import io.github.excalibase.config.ws.WsSession.CloseStatus;
import io.github.excalibase.config.ws.WsSession.TextMessage;
import graphql.parser.Parser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The caps, deadlines and queues that keep one WebSocket client from hurting the others. */
class WsSessionLimitsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SubscriptionService subscriptions;
    private WsRuntime runtime;

    @BeforeEach
    void setUp() {
        subscriptions = new SubscriptionService();
    }

    @AfterEach
    void stop() {
        runtime.stop();
    }

    private RealtimeWebSocketHandler realtime(WsLimits limits, Executor executor) {
        runtime = new WsRuntime(limits, executor);
        return withNats(new RealtimeWebSocketHandler(subscriptions, mapper, null, new WebSocketHeartbeat(0), null, null,
                runtime));
    }

    private GraphQLWebSocketHandler graphql(WsLimits limits) {
        runtime = new WsRuntime(limits, Runnable::run);
        return withNats(new GraphQLWebSocketHandler(subscriptions, mapper, null, new WebSocketHeartbeat(0), null, null,
                new ParsedQueries(1_000, Parser::parse), runtime));
    }

    private static WsLimits limits(int perProject, int perClient, int perSession, int perProjectSubs, int outbox) {
        return new WsLimits(Duration.ofSeconds(10), perProject, perClient, perSession, perProjectSubs, outbox,
                Duration.ofMillis(300));
    }

    private static WsSession session(String project, String client, List<String> sink) throws Exception {
        WsSession session = mock(WsSession.class);
        when(session.getId()).thenReturn("s-" + System.nanoTime() + "-" + project);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, project);
        attributes.put(WsRuntime.SESSION_CLIENT_KEY, client);
        when(session.getAttributes()).thenReturn(attributes);
        doAnswer(call -> {
            sink.add(((TextMessage) call.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    private void send(RealtimeWebSocketHandler handler, WsSession session, String json) throws Exception {
        handler.handleTextMessage(session, new TextMessage(json));
    }

    private static void set(Object target, String field, boolean value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setBoolean(target, value);
    }

    private static String subscribe(String id, String collection) {
        return "{\"type\":\"subscribe\",\"id\":\"" + id + "\",\"collection\":\"" + collection + "\"}";
    }

    @Test
    @DisplayName("a socket over the project's cap is closed with 1013 on open and never subscribes")
    void socketOverCapIsClosed() throws Exception {
        RealtimeWebSocketHandler handler = realtime(limits(1, 10, 5, 50, 8), Runnable::run);
        WsSession first = session("p1", "1.1.1.1", new ArrayList<>());
        WsSession second = session("p1", "2.2.2.2", new ArrayList<>());
        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);
        verify(first, never()).close(any(CloseStatus.class));
        verify(second).close(new CloseStatus(1013, "websocket_limit"));
        handler.afterConnectionClosed(second, CloseStatus.NORMAL);
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        WsSession third = session("p1", "3.3.3.3", new ArrayList<>());
        handler.afterConnectionEstablished(third);
        verify(third, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("subscriptions are capped per session with an error message, and a completed one frees a slot")
    void subscriptionsCappedPerSession() throws Exception {
        RealtimeWebSocketHandler handler = realtime(limits(10, 10, 2, 50, 8), Runnable::run);
        List<String> sent = new ArrayList<>();
        WsSession session = session("p1", "1.1.1.1", sent);
        handler.afterConnectionEstablished(session);
        runtime.initialised(session);
        send(handler, session, subscribe("a", "t1"));
        send(handler, session, subscribe("b", "t2"));
        send(handler, session, subscribe("c", "t3"));
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("\"type\":\"error\"").contains("\"id\":\"c\"")
                .contains("Too many subscriptions on this connection");
        send(handler, session, "{\"type\":\"complete\",\"id\":\"a\"}");
        send(handler, session, subscribe("c", "t3"));
        assertThat(sent).hasSize(1);
    }

    @Test
    @DisplayName("a subscribe that fails after its reservation gives the reservation back")
    void failedSubscribeReleasesItsReservation() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        subscriptions = new SubscriptionService() {
            @Override
            public Flux<CDCEvent> subscribe(String tenantId, String tableName) {
                if (fail.getAndSet(false)) {
                    throw new IllegalStateException("sink unavailable");
                }
                return super.subscribe(tenantId, tableName);
            }
        };
        RealtimeWebSocketHandler handler = realtime(limits(10, 10, 5, 1, 8), Runnable::run);
        List<String> sent = new ArrayList<>();
        WsSession session = session("p1", "1.1.1.1", sent);
        handler.afterConnectionEstablished(session);
        runtime.initialised(session);
        try {
            send(handler, session, subscribe("a", "t1"));
        } catch (RuntimeException expected) {
            // the failure may surface to the caller; what matters is the count
        }
        send(handler, session, subscribe("b", "t1"));
        assertThat(sent).noneMatch(message -> message.contains("Too many subscriptions"));
        assertThat(subscriptions.hasSubscribers(null, "public.t1")).isTrue();
    }

    private SubscriptionService closingMidSubscribe(AtomicReference<Runnable> midSubscribe) {
        return new SubscriptionService() {
            @Override
            public Flux<CDCEvent> subscribe(String tenantId, String tableName) {
                Runnable hook = midSubscribe.getAndSet(null);
                if (hook != null) {
                    hook.run();
                }
                return super.subscribe(tenantId, tableName);
            }
        };
    }

    @Test
    @DisplayName("realtime: a close that lands during a subscribe gives its project slot back")
    void realtimeCloseDuringSubscribeKeepsNoSlot() throws Exception {
        AtomicReference<Runnable> midSubscribe = new AtomicReference<>();
        subscriptions = closingMidSubscribe(midSubscribe);
        RealtimeWebSocketHandler handler = realtime(limits(10, 10, 5, 1, 8), Runnable::run);
        WsSession closing = session("p1", "1.1.1.1", new ArrayList<>());
        handler.afterConnectionEstablished(closing);
        runtime.initialised(closing);
        midSubscribe.set(() -> handler.afterConnectionClosed(closing, CloseStatus.NORMAL));
        send(handler, closing, subscribe("a", "t1"));

        List<String> sent = new ArrayList<>();
        WsSession fresh = session("p1", "2.2.2.2", sent);
        handler.afterConnectionEstablished(fresh);
        runtime.initialised(fresh);
        send(handler, fresh, subscribe("b", "t1"));
        assertThat(sent).noneMatch(message -> message.contains("Too many subscriptions"));
    }

    @Test
    @DisplayName("GraphQL: a close that lands during a subscribe gives its project slot back")
    void graphqlCloseDuringSubscribeKeepsNoSlot() throws Exception {
        AtomicReference<Runnable> midSubscribe = new AtomicReference<>();
        subscriptions = closingMidSubscribe(midSubscribe);
        GraphQLWebSocketHandler handler = graphql(limits(10, 10, 5, 1, 8));
        String query = "{\"type\":\"subscribe\",\"id\":\"1\",\"payload\":"
                + "{\"query\":\"subscription { notesChanges { operation } }\"}}";
        WsSession closing = session("p1", "1.1.1.1", new ArrayList<>());
        handler.afterConnectionEstablished(closing);
        runtime.initialised(closing);
        midSubscribe.set(() -> handler.afterConnectionClosed(closing, CloseStatus.NORMAL));
        handler.handleTextMessage(closing, new TextMessage(query));

        List<String> sent = new ArrayList<>();
        WsSession fresh = session("p1", "2.2.2.2", sent);
        handler.afterConnectionEstablished(fresh);
        runtime.initialised(fresh);
        handler.handleTextMessage(fresh, new TextMessage(query));
        assertThat(sent).noneMatch(message -> message.contains("Too many subscriptions"));
    }

    @Test
    @DisplayName("subscriptions are capped per project across sessions")
    void subscriptionsCappedPerProject() throws Exception {
        RealtimeWebSocketHandler handler = realtime(limits(10, 10, 5, 2, 8), Runnable::run);
        List<String> sent = new ArrayList<>();
        WsSession one = session("p1", "1.1.1.1", sent);
        WsSession two = session("p1", "2.2.2.2", sent);
        WsSession other = session("p2", "3.3.3.3", sent);
        for (WsSession s : List.of(one, two, other)) {
            handler.afterConnectionEstablished(s);
        }
        runtime.initialised(one);
        send(handler, one, subscribe("a", "t1"));
        runtime.initialised(two);
        send(handler, two, subscribe("a", "t1"));
        send(handler, two, subscribe("b", "t2"));
        runtime.initialised(other);
        send(handler, other, subscribe("a", "t1"));
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("Too many subscriptions for this project");
        handler.afterConnectionClosed(one, CloseStatus.NORMAL);
        send(handler, two, subscribe("b", "t2"));
        assertThat(sent).hasSize(1);
    }

    @Test
    @DisplayName("realtime: subscribe before connection_init closes the socket with 4401 and subscribes nothing")
    void realtimeSubscribeBeforeInitIsRefused() throws Exception {
        RealtimeWebSocketHandler handler = realtime(limits(10, 10, 5, 50, 8), Runnable::run);
        WsSession session = session("p1", "1.1.1.1", new ArrayList<>());
        handler.afterConnectionEstablished(session);
        send(handler, session, subscribe("a", "notes"));
        verify(session).close(new CloseStatus(4401, "connection_init_required"));
        assertThat(subscriptions.hasSubscribers("p1", "public.notes")).isFalse();
        assertThat(subscriptions.hasSubscribers(null, "public.notes")).isFalse();
    }

    @Test
    @DisplayName("realtime: a tokenless subscribe is refused and gets no events when auth is required")
    void realtimeTokenlessSubscribeGetsNothingWhenAuthRequired() throws Exception {
        RealtimeWebSocketHandler handler = realtime(limits(10, 10, 5, 50, 8), Runnable::run);
        set(handler, "jwtEnabled", true);
        set(handler, "wsAuthRequired", true);
        List<String> sent = new ArrayList<>();
        WsSession session = session("p1", "1.1.1.1", sent);
        handler.afterConnectionEstablished(session);
        // initialised without a token (as a gateway-less deployment could reach), no tenant claim in the session
        runtime.initialised(session);
        send(handler, session, subscribe("a", "notes"));
        subscriptions.publish("p1", new CDCEvent("INSERT", "public", "notes", "{\"id\":1}", 0L));
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("\"type\":\"error\"").contains("Unauthenticated");
        assertThat(subscriptions.hasSubscribers("p1", "public.notes")).isFalse();
    }

    @Test
    @DisplayName("GraphQL: subscribe before connection_init closes the socket with 4401")
    void graphqlSubscribeBeforeInitIsRefused() throws Exception {
        GraphQLWebSocketHandler handler = graphql(limits(10, 10, 5, 50, 8));
        WsSession session = session("p1", "1.1.1.1", new ArrayList<>());
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"subscribe\",\"id\":\"1\",\"payload\":"
                + "{\"query\":\"subscription { notesChanges { operation } }\"}}"));
        verify(session).close(new CloseStatus(4401, "connection_init_required"));
        assertThat(subscriptions.hasSubscribers(null, "notes")).isFalse();
    }

    @Test
    @DisplayName("GraphQL: connection_init marks the session initialised and subscribe then works")
    void graphqlSubscribeAfterInit() throws Exception {
        GraphQLWebSocketHandler handler = graphql(limits(10, 10, 5, 50, 8));
        List<String> sent = new ArrayList<>();
        WsSession session = session("p1", "1.1.1.1", sent);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"connection_init\"}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"subscribe\",\"id\":\"1\",\"payload\":"
                + "{\"query\":\"subscription { notesChanges { operation } }\"}}"));
        verify(session, never()).close(any(CloseStatus.class));
        assertThat(subscriptions.hasSubscribers(null, "notes")).isTrue();
    }

    @Test
    @DisplayName("a client that never reads is closed with 4429 once its queue overflows")
    void overflowClosesSlowConsumer() throws Exception {
        RealtimeWebSocketHandler handler = realtime(limits(10, 10, 5, 50, 3), Runnable::run);
        WsSession stuck = session("p1", "1.1.1.1", new ArrayList<>());
        when(stuck.isBackedUp()).thenReturn(true);
        handler.afterConnectionEstablished(stuck);
        runtime.initialised(stuck);
        send(handler, stuck, subscribe("a", "t"));
        for (int i = 0; i < 6; i++) {
            subscriptions.publish(null, new CDCEvent("INSERT", "public", "t", "{\"id\":" + i + "}", 0L));
        }
        verify(stuck).close(new CloseStatus(4429, "slow_consumer"));
    }

    @Test
    @DisplayName("a client whose send buffer stays full past the grace is closed with 4429")
    void stuckBufferClosesSlowConsumer() throws Exception {
        RealtimeWebSocketHandler handler = realtime(limits(10, 10, 5, 50, 100), Runnable::run);
        WsSession stuck = session("p1", "1.1.1.1", new ArrayList<>());
        when(stuck.isBackedUp()).thenReturn(true);
        handler.afterConnectionEstablished(stuck);
        runtime.initialised(stuck);
        send(handler, stuck, subscribe("a", "t"));
        subscriptions.publish(null, new CDCEvent("INSERT", "public", "t", "{\"id\":1}", 0L));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(stuck).close(new CloseStatus(4429, "slow_consumer")));
    }

    @Test
    @DisplayName("one client that never reads, or is slow to render, does not delay another project's events")
    void slowClientDoesNotDelayAnotherProject() throws Exception {
        RealtimeWebSocketHandler handler = realtime(limits(10, 10, 5, 50, 50),
                Executors.newVirtualThreadPerTaskExecutor());
        AtomicBoolean stalled = new AtomicBoolean(true);
        CountDownLatch sending = new CountDownLatch(1);
        List<String> slowSent = Collections.synchronizedList(new ArrayList<>());
        WsSession slow = session("p1", "1.1.1.1", slowSent);
        doAnswer(call -> {
            sending.countDown();
            while (stalled.get()) {
                Thread.sleep(20);
            }
            return null;
        }).when(slow).sendMessage(any(TextMessage.class));
        List<String> fastSent = Collections.synchronizedList(new ArrayList<>());
        WsSession fast = session("p2", "2.2.2.2", fastSent);
        handler.afterConnectionEstablished(slow);
        handler.afterConnectionEstablished(fast);
        runtime.initialised(slow);
        send(handler, slow, subscribe("a", "t"));
        runtime.initialised(fast);
        send(handler, fast, subscribe("a", "t"));

        // p1 and p2 are different tenants only when routed by tenant; here both see the table, which is the worst case.
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            subscriptions.publish(null, new CDCEvent("INSERT", "public", "t", "{\"id\":" + i + "}", 0L));
        }
        long publishMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(sending.await(2, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(2)).until(() -> fastSent.size() == 20);
        assertThat(publishMillis).as("publishing never waits for a subscriber").isLessThan(1_000);
        stalled.set(false);
    }

    @Test
    @DisplayName("a changed table that is not the subscribed one is dropped")
    void otherTablesAreDropped() throws Exception {
        RealtimeWebSocketHandler handler = realtime(limits(10, 10, 5, 50, 8), Runnable::run);
        List<String> sent = new ArrayList<>();
        WsSession session = session("p1", "1.1.1.1", sent);
        handler.afterConnectionEstablished(session);
        runtime.initialised(session);
        send(handler, session, subscribe("a", "secret_x"));
        subscriptions.publish(null, new CDCEvent("INSERT", "public_secret", "x", "{\"id\":1}", 0L));
        assertThat(sent).isEmpty();
        subscriptions.publish(null, new CDCEvent("INSERT", "public", "secret_x", "{\"id\":1}", 0L));
        assertThat(sent).hasSize(1);
    }

    private static RealtimeWebSocketHandler withNats(RealtimeWebSocketHandler handler) {
        handler.natsEnabled(true);
        return handler;
    }

    private static GraphQLWebSocketHandler withNats(GraphQLWebSocketHandler handler) {
        handler.natsEnabled(true);
        return handler;
    }
}
