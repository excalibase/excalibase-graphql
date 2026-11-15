package io.github.excalibase.config.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.parser.Parser;
import io.github.excalibase.access.ChangeFilter;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.compiler.ParsedQueries;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionVersions;
import io.github.excalibase.rls.PolicyChangeSubscriber;
import io.github.excalibase.schema.AccessPlans;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.excalibase.config.ws.WsSession.CloseStatus;
import io.github.excalibase.config.ws.WsSession.TextMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The close on a permission change rests on three pieces of wiring: each handler notes the document a subscription
 * opened under, the opening is checked against changes announced while its plan was resolved, and the provider's
 * refreshes are routed to the sockets. Each is pinned here by running it, not by reading it.
 */
class PermissionChangeWiringTest {

    private static final String PROJECT = "p1";
    private static final CloseStatus CHANGED = new CloseStatus(1012, "permissions_changed");

    private final ObjectMapper mapper = new ObjectMapper();
    private final WsRuntime runtime = WsRuntime.inline();

    @AfterEach
    void stop() {
        runtime.stop();
    }

    private static AccessPlans plansAt(long version) {
        AccessPlans plans = mock(AccessPlans.class);
        when(plans.realtime(any(), any(), any(), any())).thenAnswer(call -> Optional.of(
                new AccessPlans.RealtimeAccess("public.t1", mock(ChangeFilter.class), null,
                        mock(SchemaInfo.class), version)));
        return plans;
    }

    private static WsSession socket() throws Exception {
        WsSession session = mock(WsSession.class);
        when(session.getId()).thenReturn("s-" + System.nanoTime());
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, PROJECT);
        attributes.put(WsRuntime.SESSION_CLIENT_KEY, "1.1.1.1");
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    private RealtimeWebSocketHandler realtime(AccessPlans plans) {
        return new RealtimeWebSocketHandler(new SubscriptionService(), mapper, null,
                new WebSocketHeartbeat(0), plans, null, runtime);
    }

    private GraphQLWebSocketHandler graphql(AccessPlans plans) {
        return new GraphQLWebSocketHandler(new SubscriptionService(), mapper, null,
                new WebSocketHeartbeat(0), plans, null,
                new ParsedQueries(1_000, Parser::parse), runtime);
    }

    private static void subscribeRealtime(RealtimeWebSocketHandler handler, WsSession session) throws Exception {
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"subscribe\",\"id\":\"a\",\"collection\":\"t1\"}"));
    }

    private static void subscribeGraphql(GraphQLWebSocketHandler handler, WsSession session) throws Exception {
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"subscribe\",\"id\":\"a\",\"payload\":"
                + "{\"query\":\"subscription { t1Changes { operation } }\"}}"));
    }

    /** An event that reloads {@code version}, whatever the cache held before it. */
    private void eventFindingVersion(long version) {
        PermissionProvider permissions = mock(PermissionProvider.class);
        when(permissions.permissionsFor(PROJECT)).thenReturn(new PermissionSet(PROJECT, version, List.of(), List.of(), List.of()));
        new PolicyChangeSubscriber(List.of(permissions), new PermissionVersions(permissions), runtime, false,
                "nats://localhost:4222", "", "", "").evictFor("policies." + PROJECT + ".changed");
    }

    @Test
    @DisplayName("realtime: a subscribe under v7 is closed with 1012 by an event that reloads v8, and kept by one that reloads v7")
    void realtimeHandlerNotesTheOpeningVersion() throws Exception {
        RealtimeWebSocketHandler handler = realtime(plansAt(7));
        WsSession session = socket();
        handler.afterConnectionEstablished(session);
        runtime.initialised(session);
        subscribeRealtime(handler, session);

        eventFindingVersion(7);
        verify(session, never()).close(any(CloseStatus.class));
        eventFindingVersion(8);
        verify(session).close(CHANGED);
    }

    @Test
    @DisplayName("graphql: a subscribe under v7 is closed with 1012 by an event that reloads v8, and kept by one that reloads v7")
    void graphqlHandlerNotesTheOpeningVersion() throws Exception {
        GraphQLWebSocketHandler handler = graphql(plansAt(7));
        WsSession session = socket();
        handler.afterConnectionEstablished(session);
        runtime.initialised(session);
        subscribeGraphql(handler, session);

        eventFindingVersion(7);
        verify(session, never()).close(any(CloseStatus.class));
        eventFindingVersion(8);
        verify(session).close(CHANGED);
    }

    private void raceAnnouncementAgainstSubscribe(boolean realtimeHandler) throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch announced = new CountDownLatch(1);
        AccessPlans slow = mock(AccessPlans.class);
        AccessPlans fast = plansAt(7);
        when(slow.realtime(any(), any(), any(), any())).thenAnswer(call -> {
            resolving.countDown();
            assertThat(announced.await(5, TimeUnit.SECONDS)).isTrue();
            return fast.realtime(null, PROJECT, null, "t1");
        });
        WsSession session = socket();
        Thread subscriber;
        if (realtimeHandler) {
            RealtimeWebSocketHandler handler = realtime(slow);
            handler.afterConnectionEstablished(session);
            runtime.initialised(session);
            subscriber = Thread.ofVirtual().start(() -> {
                try {
                    subscribeRealtime(handler, session);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } else {
            GraphQLWebSocketHandler handler = graphql(slow);
            handler.afterConnectionEstablished(session);
            runtime.initialised(session);
            subscriber = Thread.ofVirtual().start(() -> {
                try {
                    subscribeGraphql(handler, session);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();
        eventFindingVersion(8);
        announced.countDown();
        subscriber.join(5_000);

        verify(session).close(CHANGED);
    }

    @Test
    @DisplayName("realtime: a change announced while the plan is being resolved closes the new socket")
    void realtimeOpeningRacingAnAnnouncement() throws Exception {
        raceAnnouncementAgainstSubscribe(true);
    }

    @Test
    @DisplayName("graphql: a change announced while the plan is being resolved closes the new socket")
    void graphqlOpeningRacingAnAnnouncement() throws Exception {
        raceAnnouncementAgainstSubscribe(false);
    }
}
