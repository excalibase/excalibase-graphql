package io.github.excalibase.config.ws;

import io.github.excalibase.config.ws.WsSession.CloseStatus;
import io.github.excalibase.security.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WsRuntimeTest {

    private WsRuntime runtime;

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    private static WsLimits limits(int perProject, int perClient, Duration initTimeout) {
        return new WsLimits(initTimeout, perProject, perClient, 3, 5, 4, Duration.ofSeconds(1));
    }

    private WsRuntime runtime(WsLimits limits) {
        runtime = new WsRuntime(limits, Runnable::run);
        return runtime;
    }

    private static WsSession session(String project, String client) throws Exception {
        WsSession session = mock(WsSession.class);
        when(session.getId()).thenReturn("s-" + System.nanoTime());
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, project);
        attributes.put(WsRuntime.SESSION_CLIENT_KEY, client);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    @Test
    @DisplayName("a project cannot hold more sockets than its cap, and a closed one frees its place")
    void projectCap() throws Exception {
        WsRuntime rt = runtime(limits(2, 100, Duration.ofSeconds(10)));
        WsSession first = session("p1", "10.0.0.1");
        WsSession second = session("p1", "10.0.0.2");
        assertThat(rt.admit(first)).isTrue();
        assertThat(rt.admit(second)).isTrue();
        assertThat(rt.admit(session("p1", "10.0.0.3"))).isFalse();
        assertThat(rt.admit(session("p2", "10.0.0.3"))).isTrue();
        rt.release(first);
        assertThat(rt.admit(session("p1", "10.0.0.4"))).isTrue();
    }

    @Test
    @DisplayName("one client address cannot hold more sockets than its cap, across projects")
    void clientCap() throws Exception {
        WsRuntime rt = runtime(limits(100, 2, Duration.ofSeconds(10)));
        assertThat(rt.admit(session("p1", "9.9.9.9"))).isTrue();
        assertThat(rt.admit(session("p2", "9.9.9.9"))).isTrue();
        assertThat(rt.admit(session("p3", "9.9.9.9"))).isFalse();
        assertThat(rt.admit(session("p3", "8.8.8.8"))).isTrue();
    }

    @Test
    @DisplayName("a refused socket leaves no count behind and releasing twice counts once")
    void refusedAndDoubleRelease() throws Exception {
        WsRuntime rt = runtime(limits(1, 1, Duration.ofSeconds(10)));
        WsSession admitted = session("p1", "1.1.1.1");
        assertThat(rt.admit(admitted)).isTrue();
        WsSession refused = session("p1", "2.2.2.2");
        assertThat(rt.admit(refused)).isFalse();
        rt.release(refused);
        rt.release(admitted);
        rt.release(admitted);
        assertThat(rt.admit(session("p1", "1.1.1.1"))).isTrue();
    }

    @Test
    @DisplayName("a session that sends no connection_init is closed with 4408")
    void initTimeout() throws Exception {
        WsRuntime rt = runtime(limits(10, 10, Duration.ofMillis(100)));
        WsSession idle = session("p1", "1.1.1.1");
        rt.expectInit(idle);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(idle).close(new CloseStatus(4408, "connection_init_timeout")));
    }

    @Test
    @DisplayName("a session that initialised in time stays open")
    void initialisedStaysOpen() throws Exception {
        WsRuntime rt = runtime(limits(10, 10, Duration.ofMillis(100)));
        WsSession active = session("p1", "1.1.1.1");
        rt.expectInit(active);
        rt.initialised(active);
        Thread.sleep(400);
        verify(active, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("a session is closed with 4401 when its token expires")
    void closesAtTokenExpiry() throws Exception {
        WsRuntime rt = runtime(limits(10, 10, Duration.ofSeconds(10)));
        WsSession live = session("p1", "1.1.1.1");
        live.getAttributes().put(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY, claimsExpiringIn(300));
        rt.closeAtExpiry(live);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(live).close(new CloseStatus(4401, "token_expired")));
    }

    @Test
    @DisplayName("a token with no exp, or one far away, does not close the session")
    void noExpiryNoClose() throws Exception {
        WsRuntime rt = runtime(limits(10, 10, Duration.ofSeconds(10)));
        WsSession keyed = session("p1", "1.1.1.1");
        keyed.getAttributes().put(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY,
                new JwtClaims("u", "p1", "acme", "d", "", "svc", "e", "api-key", 1L, Map.of()));
        rt.closeAtExpiry(keyed);
        WsSession later = session("p1", "1.1.1.2");
        later.getAttributes().put(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY, claimsExpiringIn(3_600_000));
        rt.closeAtExpiry(later);
        Thread.sleep(300);
        verify(keyed, never()).close(any(CloseStatus.class));
        verify(later, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("evicting a project closes its sessions with 1012 and leaves other projects alone")
    void evictClosesProjectSessions() throws Exception {
        WsRuntime rt = runtime(limits(10, 10, Duration.ofSeconds(10)));
        WsSession mine = session("p1", "1.1.1.1");
        WsSession other = session("p2", "1.1.1.1");
        rt.admit(mine);
        rt.admit(other);
        rt.evict("p1");
        verify(mine).close(new CloseStatus(1012, "permissions_changed"));
        verify(other, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("subscriptions are capped per project and the cap is given back on dispose")
    void projectSubscriptionCap() throws Exception {
        WsRuntime rt = runtime(new WsLimits(Duration.ofSeconds(10), 10, 10, 3, 2, 4, Duration.ofSeconds(1)));
        WsSession session = session("p1", "1.1.1.1");
        assertThat(rt.reserveSubscription(session)).isTrue();
        var first = rt.counted(session, () -> { });
        assertThat(rt.reserveSubscription(session)).isTrue();
        assertThat(rt.reserveSubscription(session)).isFalse();
        first.dispose();
        first.dispose();
        assertThat(rt.reserveSubscription(session)).isTrue();
        assertThat(rt.reserveSubscription(session)).isFalse();
    }

    private static JwtClaims claimsExpiringIn(long millis) {
        return new JwtClaims("u", "p1", "acme", "d", "", "user", "e", "authenticated", 0L,
                Map.of("exp", new Date(System.currentTimeMillis() + millis)));
    }

    @Test
    @DisplayName("the limits are shares of the connection bound and never below one")
    void limitsFollowTheCpus() {
        WsLimits sixteen = WsLimits.of(2, 256, 25, 5, 10, 20, 250, 256, 10);
        assertThat(sixteen.maxPerProject()).isEqualTo(128);
        assertThat(sixteen.maxPerClient()).isEqualTo(25);
        assertThat(sixteen.maxSubscriptionsPerProject()).isEqualTo(500);
        assertThat(WsLimits.of(1, 1, 1, 1, 0, 0, 0, 0, 0).maxPerClient()).isEqualTo(1);
    }
}
