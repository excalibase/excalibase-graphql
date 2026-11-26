package io.github.excalibase.rls;

import io.github.excalibase.nats.NatsOptionsFactory;
import io.github.excalibase.permissions.PermissionVersions;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Invalidates a project's cached permissions and engines the moment provisioning signals
 * a change, so edits in Studio converge immediately instead of waiting out the
 * policy-cache TTL. Subscribes with <em>core</em> NATS (not JetStream) to
 * {@code policies.*.changed}: invalidation is fire-and-forget and the TTL is the
 * fallback, so a missed message self-heals and a durable consumer is unwarranted.
 *
 * <p>Scoped to policy eviction only; CDC/DDL schema reload is a separate path.
 * Activation is decided at runtime from {@code app.nats.enabled} rather than via
 * {@code @ConditionalOnProperty}, matching the RLS bean wiring so it survives
 * GraalVM AOT. When NATS is disabled it is a safe no-op.
 */
public class PolicyChangeSubscriber {

    private static final Logger log = LoggerFactory.getLogger(PolicyChangeSubscriber.class);

    /** Wildcard that matches every project's change subject; the project is token 2. */
    static final String SUBJECT = "policies.*.changed";

    private final List<ProjectCacheEvictor> evictors;
    private final PermissionVersions permissionVersions;
    private final ProjectSockets sockets;
    private final NatsSettings settings;

    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

    /** Opens the connection; a seam so a test can stand in for a NATS that is down. */
    @FunctionalInterface
    interface Connector {
        Connection connect(Options options) throws IOException, InterruptedException;
    }

    /**
     * Where and how the bus is reached.
     *
     * @param username    bus principal, blank for an anonymous connection
     * @param password    principal secret
     * @param inboxPrefix reply-inbox prefix this principal may subscribe to
     */
    public record NatsSettings(boolean enabled, String url, String username, String password, String inboxPrefix) {}

    /** How a connection is made and how a late one is caught up. */
    record Wiring(Connector connector, Duration retryInterval, Runnable catchUp) {}

    private final Wiring wiring;
    private Connection connection;
    private Dispatcher dispatcher;
    private ScheduledExecutorService retrier;
    private boolean stopped;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * A permission change reshapes each role's schema, not just its row filters, so the built engines
     * are invalidated alongside the cached permissions, from the same signal: a schema that outlived
     * its permissions would keep serving fields the operator has just taken away.
     */
    public PolicyChangeSubscriber(List<ProjectCacheEvictor> evictors, boolean natsEnabled, String natsUrl) {
        this(evictors, natsEnabled, natsUrl, "", "", "");
    }

    public PolicyChangeSubscriber(List<ProjectCacheEvictor> evictors, boolean natsEnabled, String natsUrl,
                                  String natsUsername, String natsPassword, String natsInboxPrefix) {
        this(evictors, null, null, new NatsSettings(natsEnabled, natsUrl, natsUsername, natsPassword, natsInboxPrefix),
                () -> { });
    }

    public PolicyChangeSubscriber(List<ProjectCacheEvictor> evictors, PermissionVersions permissionVersions,
                                  ProjectSockets sockets, boolean natsEnabled, String natsUrl,
                                  String natsUsername, String natsPassword, String natsInboxPrefix) {
        this(evictors, permissionVersions, sockets,
                new NatsSettings(natsEnabled, natsUrl, natsUsername, natsPassword, natsInboxPrefix), () -> { });
    }

    /**
     * @param permissionVersions compares the permission document before and after the eviction
     * @param sockets     the project's open sockets, closed only when the document actually changed
     * @param catchUp     evicts everything held, run when NATS connects late: signals sent while it was
     *                    unreachable were missed
     */
    public PolicyChangeSubscriber(List<ProjectCacheEvictor> evictors, PermissionVersions permissionVersions,
                                  ProjectSockets sockets, NatsSettings settings, Runnable catchUp) {
        this(evictors, permissionVersions, sockets, settings, new Wiring(Nats::connect, RETRY_INTERVAL, catchUp));
    }

    PolicyChangeSubscriber(List<ProjectCacheEvictor> evictors, PermissionVersions permissionVersions,
                           ProjectSockets sockets, NatsSettings settings, Wiring wiring) {
        this.wiring = wiring;
        this.evictors = List.copyOf(evictors);
        this.permissionVersions = permissionVersions;
        this.sockets = sockets;
        this.settings = settings;
    }

    public void start() {
        if (!settings.enabled()) {
            log.info("Policy-change subscriber disabled (app.nats.enabled=false); "
                    + "policy cache converges via TTL only");
            return;
        }
        if (connectAndSubscribe()) {
            return;
        }
        // NATS may be down while the engine boots: try again until it answers or the subscriber stops.
        retrier = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "policy-change-connect");
            thread.setDaemon(true);
            return thread;
        });
        long period = wiring.retryInterval().toMillis();
        retrier.scheduleWithFixedDelay(this::retryUntilConnected, period, period, TimeUnit.MILLISECONDS);
    }

    private void retryUntilConnected() {
        if (connectAndSubscribe()) {
            retrier.shutdown();
            if (running.get()) {
                log.info("Policy-change subscriber connected late; evicting everything held, signals were missed");
                wiring.catchUp().run();
                settleOpenSockets();
            }
        }
    }

    /**
     * A permission change made while NATS was down was never signalled, and the evicted documents leave no earlier
     * copy to compare a refresh with. So every project that has sockets is settled as a signal would: against the
     * document as it is now, the sockets opened under another one closing.
     */
    private void settleOpenSockets() {
        if (sockets == null || permissionVersions == null) {
            return;
        }
        for (String projectId : sockets.projectsWithSockets()) {
            try {
                settleSockets(projectId, false);
            } catch (RuntimeException e) {
                log.error("Settling the sockets of project {} after the late connect failed; closing them", projectId, e);
                sockets.closeForPermissionChange(projectId);
            }
        }
    }

    /** The dial options, with the listener that catches up after a reconnect. */
    Options options() {
        return NatsOptionsFactory
                .builder(settings.url(), settings.username(), settings.password(), settings.inboxPrefix())
                .connectionListener((conn, event) -> connectionEvent(event))
                .build();
    }

    /** A reconnect missed every signal sent while the bus was unreachable: evict what is held and settle the sockets. */
    void connectionEvent(ConnectionListener.Events event) {
        if (event != ConnectionListener.Events.RECONNECTED) {
            return;
        }
        log.info("Policy-change subscriber reconnected; evicting everything held, signals were missed");
        try {
            wiring.catchUp().run();
        } catch (RuntimeException e) {
            log.error("Evicting after the NATS reconnect failed; the TTL still converges", e);
        }
        settleOpenSockets();
    }

    /** True once subscribed, or once stopped, when there is nothing left to try. */
    private synchronized boolean connectAndSubscribe() {
        if (stopped) {
            return true;
        }
        try {
            connection = wiring.connector().connect(options());
            dispatcher = connection.createDispatcher(this::handleMessage);
            dispatcher.subscribe(SUBJECT);
            running.set(true);
            log.info("Policy-change subscriber started - subscribing to '{}'", SUBJECT);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while starting policy-change subscriber", e);
            closeConnection();
            return true;
        } catch (Exception e) {
            log.warn("Policy-change subscriber not connected yet, retrying in {}: {}", wiring.retryInterval(),
                    e.getMessage());
            closeConnection();
            return false;
        }
    }

    /** A connection that did not end up subscribed is closed, not left to leak while the next attempt opens another. */
    private void closeConnection() {
        Connection open = connection;
        connection = null;
        dispatcher = null;
        if (open == null) {
            return;
        }
        try {
            open.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.debug("Closing an unsubscribed NATS connection failed: {}", e.getMessage());
        }
    }

    public synchronized void stop() {
        stopped = true;
        if (retrier != null) {
            retrier.shutdownNow();
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    private void handleMessage(Message msg) {
        try {
            evictFor(msg.getSubject());
        } catch (Exception e) {
            // Never let a bad message kill the dispatcher; TTL still converges.
            log.error("Failed to process policy-change message: subject={}", msg.getSubject(), e);
        }
    }

    /**
     * Evicts the project named by {@code subject}'s 2nd token. Ignores any subject
     * that is not exactly {@code policies.{projectId}.changed} with a single,
     * non-empty, non-wildcard project token — dropping an invalidation is safe
     * (TTL catches up), acting on a malformed one is not.
     */
    public void evictFor(String subject) {
        String projectId = parseProjectId(subject);
        if (projectId == null) {
            log.warn("Ignoring policy-change on malformed subject: {}", subject);
            return;
        }
        log.info("Policy change for project {} - evicting cached policies and engines", projectId);
        boolean watched = sockets != null && sockets.hasSockets(projectId);
        RuntimeException failure = evictAll(projectId);
        if (watched) {
            settleSockets(projectId, failure != null);
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Every holder is asked even if one fails; the first failure is returned. */
    private RuntimeException evictAll(String projectId) {
        RuntimeException failure = null;
        for (ProjectCacheEvictor evictor : evictors) {
            try {
                evictor.evict(projectId);
            } catch (RuntimeException e) {
                failure = failure == null ? e : failure;
            }
        }
        return failure;
    }

    /**
     * The sockets stream under the plan worked out when each subscription opened, so only a close protects them
     * from a permission change. An event that leaves the document as the sockets hold it (a plain statement run in
     * Studio publishes one too) closes nothing. When the document cannot be read back, or anything failed on the
     * way, every socket of the project closes: they subscribe again under whatever is current.
     */
    private void settleSockets(String projectId, boolean failed) {
        OptionalLong current = failed ? OptionalLong.empty() : permissionVersions.current(projectId);
        if (current.isPresent()) {
            sockets.permissionsAre(projectId, current.getAsLong());
        } else {
            sockets.closeForPermissionChange(projectId);
        }
    }

    static String parseProjectId(String subject) {
        if (subject == null) {
            return null;
        }
        String[] tokens = subject.split("\\.", -1);
        if (tokens.length != 3 || !"policies".equals(tokens[0]) || !"changed".equals(tokens[2])) {
            return null;
        }
        String projectId = tokens[1];
        if (projectId.isBlank() || projectId.contains("*") || projectId.contains(">")) {
            return null;
        }
        return projectId;
    }
}
