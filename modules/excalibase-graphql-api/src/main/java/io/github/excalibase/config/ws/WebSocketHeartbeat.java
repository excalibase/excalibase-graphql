package io.github.excalibase.config.ws;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.github.excalibase.config.ws.WsSession.CloseStatus;
import io.github.excalibase.config.ws.WsSession.TextMessage;
import jakarta.inject.Singleton;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends a native WebSocket PING frame to every open session on a fixed
 * interval. Idle WebSocket connections are otherwise reaped by proxies, load
 * balancers, and NAT gateways after ~30–60s of silence — the single most common
 * cause of "the socket just dropped". A periodic ping keeps the path warm;
 * compliant clients answer with PONG automatically at the protocol level, so no
 * application-protocol cooperation is required.
 *
 * <p>Sends are serialised per session (see {@link #ping}) because a session may
 * already be written to from several CDC fan-out threads, and a raw
 * {@link WsSession} is not safe for concurrent sends.
 *
 * <p>Disabled by setting {@code app.websocket.heartbeat-seconds} to 0.
 */
@Singleton
public class WebSocketHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(WebSocketHeartbeat.class);
    

    private final Set<WsSession> sessions = ConcurrentHashMap.newKeySet();
    private final long intervalSeconds;
    private ScheduledExecutorService scheduler;

    public WebSocketHeartbeat(@Value("${app.websocket.heartbeat-seconds:25}") long intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    @PostConstruct
    void start() {
        if (intervalSeconds <= 0) {
            log.info("WebSocket heartbeat disabled (app.websocket.heartbeat-seconds=0)");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::pingAll, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("WebSocket heartbeat started ({}s interval)", intervalSeconds);
    }

    /** Registers a session to receive heartbeats. Idempotent. */
    public void register(WsSession session) {
        sessions.add(session);
    }

    /** Stops heartbeating a session (call on close). */
    public void unregister(WsSession session) {
        sessions.remove(session);
    }

    /** Visible for testing — exercised by the scheduler in production. */
    void pingAll() {
        for (WsSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            ping(session);
        }
    }

    /**
     * Sends one PING, serialising on the session monitor so it never races a
     * concurrent data send. Handlers that write to the session must synchronise
     * on the same monitor for this to hold.
     */
    private void ping(WsSession session) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendPing();
                }
            }
        } catch (Exception e) {
            log.debug("Heartbeat ping failed for session {}: {}", session.getId(), e.getMessage());
            sessions.remove(session);
        }
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
