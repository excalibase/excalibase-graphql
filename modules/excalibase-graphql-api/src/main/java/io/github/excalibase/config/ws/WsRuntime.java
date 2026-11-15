package io.github.excalibase.config.ws;

import io.github.excalibase.rls.ProjectSockets;
import io.github.excalibase.security.JwtClaims;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything the two WebSocket handlers share to keep one client from hurting the rest: caps on sockets and
 * subscriptions, the {@code connection_init} deadline, closing at token expiry or when a project's permissions
 * change, and the per-session delivery queues.
 */
@Component
public class WsRuntime implements ProjectSockets {

    private static final Logger log = LoggerFactory.getLogger(WsRuntime.class);
    static final String SESSION_CLIENT_KEY = "excalibase.clientAddress";
    private static final String ADMITTED_KEY = "excalibase.ws.admitted";
    static final String INITIALISED_KEY = "excalibase.ws.initialised";
    private static final String OUTBOX_KEY = "excalibase.ws.outbox";
    private static final String EXPIRY_KEY = "excalibase.ws.expiry";

    private final WsLimits limits;
    private final ScheduledExecutorService scheduler;
    private final Executor delivery;
    private final Map<String, Integer> socketsByProject = new ConcurrentHashMap<>();
    private final Map<String, Integer> socketsByClient = new ConcurrentHashMap<>();
    private final Map<String, Integer> subscriptionsByProject = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> sessionsByProject = new ConcurrentHashMap<>();

    @Autowired
    public WsRuntime(
            @Value("${app.overload.max-connections-per-cpu:256}") int connectionsPerCpu,
            @Value("${app.websocket.max-per-project-percent:25}") int projectPercent,
            @Value("${app.websocket.max-per-client-percent:5}") int clientPercent,
            @Value("${app.websocket.init-timeout-seconds:10}") int initTimeoutSeconds,
            @Value("${app.websocket.max-subscriptions-per-session:20}") int subscriptionsPerSession,
            @Value("${app.websocket.max-subscriptions-per-project-per-cpu:250}") int subscriptionsPerProjectPerCpu,
            @Value("${app.websocket.outbox-capacity:256}") int outboxCapacity,
            @Value("${app.websocket.slow-consumer-seconds:10}") int slowConsumerSeconds) {
        this(WsLimits.of(Runtime.getRuntime().availableProcessors(), connectionsPerCpu, projectPercent,
                clientPercent, initTimeoutSeconds, subscriptionsPerSession, subscriptionsPerProjectPerCpu,
                outboxCapacity, slowConsumerSeconds), Executors.newVirtualThreadPerTaskExecutor());
    }

    WsRuntime(WsLimits limits, Executor delivery) {
        this.limits = limits;
        this.delivery = delivery;
        this.scheduler = Executors.newScheduledThreadPool(1, task -> {
            Thread thread = new Thread(task, "ws-runtime");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Limits that never get in the way and deliver on the calling thread, for tests of one handler. */
    static WsRuntime inline() {
        return new WsRuntime(WsLimits.of(1, 1_000_000, 100, 100, 10, 1_000_000, 1_000_000, 1_000_000, 10),
                Runnable::run);
    }

    // -- sockets --

    /** Counts a new socket against its project and client; false (nothing counted) when either is at its cap. */
    boolean admit(WebSocketSession session) {
        Map<String, Object> attributes = session.getAttributes();
        String project = (String) attributes.get(GraphQLWebSocketHandler.SESSION_PROJECT_KEY);
        String client = (String) attributes.get(SESSION_CLIENT_KEY);
        if (project != null && !acquire(socketsByProject, project, limits.maxPerProject())) {
            return false;
        }
        if (client != null && !acquire(socketsByClient, client, limits.maxPerClient())) {
            drop(socketsByProject, project);
            return false;
        }
        attributes.put(ADMITTED_KEY, Boolean.TRUE);
        if (project != null) {
            sessionsByProject.computeIfAbsent(project, key -> ConcurrentHashMap.newKeySet()).add(session);
        }
        return true;
    }

    /** Gives back what {@link #admit} counted, stops the session's timers and queue. Safe to call twice. */
    void release(WebSocketSession session) {
        Map<String, Object> attributes = session.getAttributes();
        cancelExpiry(attributes);
        Object outbox = attributes.remove(OUTBOX_KEY);
        if (outbox instanceof SessionOutbox queue) {
            queue.close();
        }
        if (attributes.remove(ADMITTED_KEY) == null) {
            return;
        }
        String project = (String) attributes.get(GraphQLWebSocketHandler.SESSION_PROJECT_KEY);
        drop(socketsByProject, project);
        drop(socketsByClient, (String) attributes.get(SESSION_CLIENT_KEY));
        if (project != null) {
            sessionsByProject.computeIfPresent(project, (key, sessions) -> {
                sessions.remove(session);
                return sessions.isEmpty() ? null : sessions;
            });
        }
    }

    /** Closes the session unless its {@code connection_init} has arrived within the deadline. */
    void expectInit(WebSocketSession session) {
        scheduler.schedule(() -> {
            if (session.isOpen() && !isInitialised(session)) {
                close(session, WsClose.INIT_TIMEOUT);
            }
        }, limits.initTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    void initialised(WebSocketSession session) {
        session.getAttributes().put(INITIALISED_KEY, Boolean.TRUE);
    }

    boolean isInitialised(WebSocketSession session) {
        return session.getAttributes().containsKey(INITIALISED_KEY);
    }

    // -- token expiry --

    /** Closes the session when its token's {@code exp} passes; tokens without one are left alone. */
    void closeAtExpiry(WebSocketSession session) {
        Map<String, Object> attributes = session.getAttributes();
        cancelExpiry(attributes);
        Object claims = attributes.get(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY);
        Instant expiry = claims instanceof JwtClaims jwt ? expiryOf(jwt) : null;
        if (expiry == null) {
            return;
        }
        long delay = Math.max(0, expiry.toEpochMilli() - System.currentTimeMillis());
        attributes.put(EXPIRY_KEY, scheduler.schedule(() -> close(session, WsClose.TOKEN_EXPIRED),
                delay, TimeUnit.MILLISECONDS));
    }

    private static void cancelExpiry(Map<String, Object> attributes) {
        if (attributes.remove(EXPIRY_KEY) instanceof ScheduledFuture<?> pending) {
            pending.cancel(false);
        }
    }

    static Instant expiryOf(JwtClaims claims) {
        Object exp = claims.extraClaims().get("exp");
        if (exp instanceof Date date) {
            return date.toInstant();
        }
        if (exp instanceof Instant instant) {
            return instant;
        }
        return exp instanceof Number seconds ? Instant.ofEpochSecond(seconds.longValue()) : null;
    }

    // -- permissions --

    @Override
    public boolean hasSockets(String projectId) {
        Set<WebSocketSession> sessions = sessionsByProject.get(projectId);
        return sessions != null && !sessions.isEmpty();
    }

    /** A project's permissions changed: its sockets close so clients subscribe again under the new ones. */
    @Override
    public void closeForPermissionChange(String projectId) {
        Set<WebSocketSession> sessions = sessionsByProject.get(projectId);
        if (sessions == null) {
            return;
        }
        log.info("Closing {} WebSocket sessions of project {}: permissions changed", sessions.size(), projectId);
        for (WebSocketSession session : Set.copyOf(sessions)) {
            close(session, WsClose.PERMISSIONS_CHANGED);
        }
    }

    // -- subscriptions --

    /** Counts a subscription against the project; false when the project is at its cap. */
    boolean reserveSubscription(WebSocketSession session) {
        String project = (String) session.getAttributes().get(GraphQLWebSocketHandler.SESSION_PROJECT_KEY);
        return project == null || acquire(subscriptionsByProject, project, limits.maxSubscriptionsPerProject());
    }

    /** Gives back a reservation whose subscription never started. */
    void cancelReservation(WebSocketSession session) {
        String project = (String) session.getAttributes().get(GraphQLWebSocketHandler.SESSION_PROJECT_KEY);
        if (project != null) {
            drop(subscriptionsByProject, project);
        }
    }

    /** Wraps a reserved subscription so that disposing it, once, gives the reservation back. */
    Disposable counted(WebSocketSession session, Disposable subscription) {
        String project = (String) session.getAttributes().get(GraphQLWebSocketHandler.SESSION_PROJECT_KEY);
        AtomicBoolean disposed = new AtomicBoolean();
        return () -> {
            if (disposed.compareAndSet(false, true)) {
                subscription.dispose();
                drop(subscriptionsByProject, project);
            }
        };
    }

    boolean atSessionCap(int subscriptions) {
        return subscriptions >= limits.maxSubscriptionsPerSession();
    }

    /** The session's delivery queue, made on first use. */
    SessionOutbox outbox(WebSocketSession session) {
        return (SessionOutbox) session.getAttributes().computeIfAbsent(OUTBOX_KEY,
                key -> new SessionOutbox(session, this, delivery, scheduler, limits));
    }

    // -- closing --

    /**
     * Closes the session. Not under the session monitor the senders take: a send stuck on a client that does
     * not read holds it, and the close is what ends that send.
     */
    void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException | RuntimeException failure) {
            log.debug("Closing session {} failed: {}", session.getId(), failure.toString());
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
        if (delivery instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception failure) {
                log.debug("Delivery executor did not stop cleanly: {}", failure.toString());
            }
        }
    }

    private static boolean acquire(Map<String, Integer> counts, String key, int max) {
        boolean[] admitted = {false};
        counts.compute(key, (name, current) -> {
            int count = current == null ? 0 : current;
            if (count >= max) {
                return current;
            }
            admitted[0] = true;
            return count + 1;
        });
        return admitted[0];
    }

    private static void drop(Map<String, Integer> counts, String key) {
        if (key != null) {
            counts.computeIfPresent(key, (name, count) -> count <= 1 ? null : count - 1);
        }
    }
}
