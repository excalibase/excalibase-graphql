package io.github.excalibase.config.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.config.ws.WsSession.CloseStatus;
import io.github.excalibase.config.ws.WsSession.TextMessage;
import io.micronaut.websocket.WebSocketSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every write to one session (data, ping, close) holds the session's single {@link WsSession#sendLock()}. The
 * tests hold that lock from outside and show each writer waits for it: a writer on another monitor would not.
 */
class WsSendLockTest {

    private final ExecutorService threads = Executors.newCachedThreadPool();

    @AfterEach
    void stop() {
        threads.shutdownNow();
    }

    private static WsSession session(Object lock) {
        WsSession session = mock(WsSession.class);
        when(session.getId()).thenReturn("lock-" + System.nanoTime());
        when(session.isOpen()).thenReturn(true);
        when(session.sendLock()).thenReturn(lock);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put(WsRuntime.INITIALISED_KEY, Boolean.TRUE);
        attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, "p1");
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    /** Runs the writer while this thread holds the lock; true if the writer finished before the lock was freed. */
    private boolean writerPassesHeldLock(Object lock, Runnable writer) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        synchronized (lock) {
            threads.submit(() -> {
                writer.run();
                done.countDown();
            });
            return done.await(300, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    @DisplayName("the heartbeat ping waits for the session send lock")
    void heartbeatPing() throws Exception {
        Object lock = new Object();
        WsSession session = session(lock);
        WebSocketHeartbeat heartbeat = new WebSocketHeartbeat(0);
        heartbeat.register(session);

        assertThat(writerPassesHeldLock(lock, heartbeat::pingAll)).isFalse();
        verify(session, timeout(2000).atLeastOnce()).sendPing();
    }

    @Test
    @DisplayName("closing a session waits for the session send lock")
    void runtimeClose() throws Exception {
        Object lock = new Object();
        WsSession session = session(lock);
        WsRuntime runtime = new WsRuntime(new WsLimits(Duration.ofSeconds(10), 10, 10, 3, 5, 4, Duration.ofSeconds(1)),
                Runnable::run);
        try {
            assertThat(writerPassesHeldLock(lock, () -> runtime.close(session, CloseStatus.NORMAL))).isFalse();
            verify(session, timeout(2000)).close(CloseStatus.NORMAL);
        } finally {
            runtime.stop();
        }
    }

    @Test
    @DisplayName("a realtime delivery waits for the session send lock")
    void realtimeSend() throws Exception {
        Object lock = new Object();
        WsSession session = session(lock);
        SubscriptionService service = new SubscriptionService();
        ObjectMapper mapper = new ObjectMapper();
        RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(service, mapper, null,
                new WebSocketHeartbeat(0), null, null);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "subscribe", "id", "s1", "collection", "customers"))));

        boolean passed = writerPassesHeldLock(lock, () -> service.publish(null,
                new CDCEvent("INSERT", "public", "customers", "{\"name\":\"Vu\"}", 0L)));

        assertThat(passed).isFalse();
        verify(session, timeout(2000).atLeastOnce()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("a graphql-ws reply waits for the session send lock")
    void graphqlSend() throws Exception {
        Object lock = new Object();
        WsSession session = session(lock);
        GraphQLWebSocketHandler handler = new GraphQLWebSocketHandler(new SubscriptionService(), new ObjectMapper(),
                null, new WebSocketHeartbeat(0), null, null,
                new io.github.excalibase.compiler.ParsedQueries(1_000, graphql.parser.Parser::parse));

        boolean passed = writerPassesHeldLock(lock, () -> {
            try {
                handler.handleTextMessage(session, new TextMessage("{\"type\":\"connection_init\"}"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(passed).isFalse();
        verify(session, timeout(2000).atLeastOnce()).sendMessage(any(TextMessage.class));
    }

    /** A session that keeps what is put on it, like a real one. */
    private static WebSocketSession storingSession() {
        Map<String, Object> stored = new ConcurrentHashMap<>();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.put(any(), any())).thenAnswer(call -> {
            stored.put(call.getArgument(0), call.getArgument(1));
            return session;
        });
        when(session.get(any(CharSequence.class), any(Class.class))).thenAnswer(call ->
                Optional.ofNullable(stored.get(call.getArgument(0).toString())));
        return session;
    }

    @Test
    @DisplayName("every call for one Micronaut session hands back the same lock, and sessions do not share one")
    void micronautSessionLockIsStable() {
        WebSocketSession first = storingSession();
        WebSocketSession second = storingSession();
        MicronautWsSession a = MicronautWsSession.open(first, new ConcurrentHashMap<>());
        MicronautWsSession b = MicronautWsSession.open(second, new ConcurrentHashMap<>());

        assertThat(MicronautWsSession.of(first)).isSameAs(a);
        assertThat(MicronautWsSession.of(first).sendLock()).isSameAs(a.sendLock());
        assertThat(a.sendLock()).isSameAs(a.sendLock()).isNotSameAs(a).isNotSameAs(b.sendLock());
        assertThat(WsSession.lockOf(a)).isSameAs(a.sendLock());
    }

    @Test
    @DisplayName("a session with no lock of its own is locked on itself")
    void lockOfFallsBackToTheSession() throws Exception {
        WsSession session = mock(WsSession.class);
        assertThat(WsSession.lockOf(session)).isSameAs(session);
        verify(session, never()).sendMessage(any());
    }
}
