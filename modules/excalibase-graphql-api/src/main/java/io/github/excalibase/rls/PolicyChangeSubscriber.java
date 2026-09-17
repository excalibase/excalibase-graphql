package io.github.excalibase.rls;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Invalidates a project's cached RLS/CLS policies the moment provisioning signals
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
    private final boolean natsEnabled;
    private final String natsUrl;

    private Connection connection;
    private Dispatcher dispatcher;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PolicyChangeSubscriber(PolicyProvider policyProvider, boolean natsEnabled, String natsUrl) {
        this(List.of(policyProvider), natsEnabled, natsUrl);
    }

    /**
     * A grant change reshapes the caller's schema, not just their row filters, so
     * the built engines are invalidated alongside the policies. Both caches are
     * evicted from the same signal — a schema that outlived its grants would keep
     * serving fields the operator has just taken away.
     */
    public PolicyChangeSubscriber(List<ProjectCacheEvictor> evictors, boolean natsEnabled, String natsUrl) {
        this.evictors = List.copyOf(evictors);
        this.natsEnabled = natsEnabled;
        this.natsUrl = natsUrl;
    }

    @PostConstruct
    public void start() {
        if (!natsEnabled) {
            log.info("Policy-change subscriber disabled (app.nats.enabled=false); "
                    + "policy cache converges via TTL only");
            return;
        }
        try {
            Options options = Options.builder()
                    .server(natsUrl)
                    .reconnectWait(Duration.ofSeconds(2))
                    .maxReconnects(-1)
                    .build();
            connection = Nats.connect(options);
            dispatcher = connection.createDispatcher(this::handleMessage);
            dispatcher.subscribe(SUBJECT);
            running.set(true);
            log.info("Policy-change subscriber started - subscribing to '{}'", SUBJECT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while starting policy-change subscriber", e);
        } catch (Exception e) {
            log.error("Failed to start policy-change subscriber", e);
        }
    }

    @PreDestroy
    public void stop() {
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
    void evictFor(String subject) {
        String projectId = parseProjectId(subject);
        if (projectId == null) {
            log.warn("Ignoring policy-change on malformed subject: {}", subject);
            return;
        }
        log.info("Policy change for project {} - evicting cached policies and engines", projectId);
        evictors.forEach(evictor -> evictor.evict(projectId));
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
