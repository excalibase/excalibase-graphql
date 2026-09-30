package io.github.excalibase.permissions;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.excalibase.security.TokenFileSource;
import io.github.excalibase.security.TokenUnavailableException;
import io.github.excalibase.security.UnknownProjectException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Reads a project's permission document from the control plane
 * ({@code GET {base}/provision/{projectId}/permissions/}) and caches it per project for the TTL of its {@link PermissionCachePolicy}.
 *
 * <ul>
 *   <li>404: the project does not exist; its cached copy is dropped and nothing is cached.</li>
 *   <li>The control plane cannot be read (IO, timeout, non-200, no token): the last good copy is served
 *       while the first failure is younger than max-stale, else {@link PermissionsUnavailableException}.
 *       Through an outage the control plane is asked at most once per retry interval; reads in between
 *       answer from the cache or refuse at once. Every failed attempt is logged at ERROR and counted; the
 *       staleness is a gauge per project.</li>
 *   <li>Concurrent reads of one project share a single fetch.</li>
 *   <li>A document that breaks the grammar is refused, never cached and never partly applied. The last
 *       good copy stands in for at most one TTL from the first refusal; after that the project is
 *       unavailable until a valid document arrives. Every refusal is logged and counted.</li>
 * </ul>
 */
public final class ProvisioningPermissionProvider implements PermissionProvider {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningPermissionProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Counter of refused documents, tagged by project; Prometheus shows it with a {@code _total} suffix. */
    private static final String PROJECT_TAG = "project";

    public static final String REFUSED_METRIC = "excalibase.permissions.document.refused";
    /** Counter of failed refreshes, tagged by project. */
    public static final String FETCH_FAILED_METRIC = "excalibase.permissions.fetch.failed";
    /** Gauge of how long the project's refreshes have been failing, in seconds; 0 when fresh. */
    public static final String STALE_SECONDS_METRIC = "excalibase.permissions.stale.seconds";

    /** A repeated key would otherwise let the last one silently win. */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;
    private final TokenFileSource tokenSource;
    private final PermissionCachePolicy policy;
    private final LongSupplier clock;
    private final MeterRegistry meters;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PermissionSet>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAttemptAt = new ConcurrentHashMap<>();
    private final Map<String, Long> firstRefusalAt = new ConcurrentHashMap<>();
    private final Map<String, Long> firstFailureAt = new ConcurrentHashMap<>();
    private final Set<String> expiryReported = ConcurrentHashMap.newKeySet();
    private final Set<String> gaugedProjects = ConcurrentHashMap.newKeySet();

    private record Cached(PermissionSet permissions, long fetchedAt) {}

    /** A document the control plane served but the grammar refused. */
    private static final class RefusedDocumentException extends PermissionsUnavailableException {
        RefusedDocumentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ProvisioningPermissionProvider(String baseUrl, TokenFileSource tokenSource, PermissionCachePolicy policy,
                                          MeterRegistry meters) {
        this(baseUrl, tokenSource, policy, System::currentTimeMillis, meters);
    }

    ProvisioningPermissionProvider(String baseUrl, TokenFileSource tokenSource, PermissionCachePolicy policy,
                                   LongSupplier clock, MeterRegistry meters) {
        String base = Objects.requireNonNull(baseUrl, "baseUrl");
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    @Override
    public PermissionSet permissionsFor(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is required");
        }
        Cached current = cache.get(projectId);
        if (isFresh(current, clock.getAsLong())) {
            return current.permissions();
        }
        CompletableFuture<PermissionSet> mine = new CompletableFuture<>();
        CompletableFuture<PermissionSet> running = inFlight.putIfAbsent(projectId, mine);
        if (running != null) {
            return join(running);
        }
        try {
            PermissionSet permissions = readOnce(projectId);
            mine.complete(permissions);
            return permissions;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(projectId, mine);
        }
    }

    /** Callers that arrive while a read is in flight share its outcome instead of starting another. */
    private static PermissionSet join(CompletableFuture<PermissionSet> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private boolean isFresh(Cached cached, long nowMs) {
        return cached != null && nowMs - cached.fetchedAt() < policy.ttlMillis();
    }

    /** One read for all current callers: the cache if a previous read just refilled it, else one attempt. */
    private PermissionSet readOnce(String projectId) {
        long nowMs = clock.getAsLong();
        Cached current = cache.get(projectId);
        if (isFresh(current, nowMs)) {
            return current.permissions();
        }
        if (!attemptDue(projectId, nowMs)) {
            return withoutAttempt(projectId, current, nowMs);
        }
        lastAttemptAt.put(projectId, nowMs);
        try {
            PermissionSet fresh = fetch(projectId);
            cache.put(projectId, new Cached(fresh, nowMs));
            firstRefusalAt.remove(projectId);
            endOutage(projectId);
            return fresh;
        } catch (UnknownProjectException e) {
            evict(projectId);
            throw e;
        } catch (RefusedDocumentException e) {
            return withinRefusalWindow(projectId, current, nowMs, e);
        } catch (PermissionsUnavailableException e) {
            return afterFailedAttempt(projectId, current, nowMs, e);
        }
    }

    /** Outside an outage every read may ask; during one, at most one attempt per retry interval. */
    private boolean attemptDue(String projectId, long nowMs) {
        Long lastAttempt = lastAttemptAt.get(projectId);
        return !firstFailureAt.containsKey(projectId) || lastAttempt == null
                || nowMs - lastAttempt >= policy.retryIntervalMillis();
    }

    /** Between attempts in an outage: the cached copy while the window is open, else unavailable at once. */
    private PermissionSet withoutAttempt(String projectId, Cached current, long nowMs) {
        long staleForMs = nowMs - firstFailureAt.getOrDefault(projectId, nowMs);
        if (current == null) {
            throw new PermissionsUnavailableException("the permissions of " + projectId
                    + " cannot be read and none are cached");
        }
        return withinStaleWindow(projectId, current, staleForMs, null);
    }

    /** Logs and counts the failed attempt, then answers as {@link #withoutAttempt} would. */
    private PermissionSet afterFailedAttempt(String projectId, Cached current, long nowMs,
                                             PermissionsUnavailableException failure) {
        long staleForMs = nowMs - firstFailureAt.computeIfAbsent(projectId, ignored -> nowMs);
        trackStaleness(projectId);
        log.error("permissions_fetch_failed project={} stale_for_ms={} reason={}",
                projectId, staleForMs, failure.getMessage());
        Counter.builder(FETCH_FAILED_METRIC).tag(PROJECT_TAG, projectId).register(meters).increment();
        if (current == null) {
            throw failure;
        }
        return withinStaleWindow(projectId, current, staleForMs, failure);
    }

    /** The last good copy while the first failed refresh is younger than max-stale; unavailable after. */
    private PermissionSet withinStaleWindow(String projectId, Cached current, long staleForMs,
                                            PermissionsUnavailableException failure) {
        if (staleForMs < policy.maxStaleMillis()) {
            return current.permissions();
        }
        if (expiryReported.add(projectId)) {
            log.error("permissions_stale_expired project={} stale_for_ms={} max_stale_ms={}",
                    projectId, staleForMs, policy.maxStaleMillis());
        }
        throw new PermissionsUnavailableException("the cached permissions of " + projectId
                + " expired after " + staleForMs + " ms without a successful refresh", failure);
    }

    private void endOutage(String projectId) {
        firstFailureAt.remove(projectId);
        expiryReported.remove(projectId);
        trackStaleness(projectId);
    }

    private void trackStaleness(String projectId) {
        if (gaugedProjects.add(projectId)) {
            Gauge.builder(STALE_SECONDS_METRIC, () -> staleSeconds(projectId))
                    .tag(PROJECT_TAG, projectId)
                    .strongReference(true)
                    .register(meters);
        }
    }

    private double staleSeconds(String projectId) {
        Long since = firstFailureAt.get(projectId);
        return since == null ? 0 : (clock.getAsLong() - since) / 1000.0;
    }

    /** The last good copy while the first refusal is younger than one TTL; unavailable after. */
    private PermissionSet withinRefusalWindow(String projectId, Cached current, long nowMs,
                                              RefusedDocumentException refusal) {
        long since = firstRefusalAt.computeIfAbsent(projectId, ignored -> nowMs);
        if (current == null || nowMs - since >= policy.ttlMillis()) {
            throw refusal;
        }
        log.warn("permissions_stale project={} version={} reason=document_refused",
                projectId, current.permissions().version());
        return current.permissions();
    }

    @Override
    public void evict(String projectId) {
        cache.remove(projectId);
        lastAttemptAt.remove(projectId);
        firstRefusalAt.remove(projectId);
        firstFailureAt.remove(projectId);
        expiryReported.remove(projectId);
    }

    private PermissionSet fetch(String projectId) {
        HttpResponse<String> response = send(projectId);
        if (response.statusCode() == 404) {
            throw new UnknownProjectException(projectId);
        }
        if (response.statusCode() != 200) {
            throw new PermissionsUnavailableException(
                    "control plane answered HTTP " + response.statusCode() + " for the permissions of " + projectId);
        }
        return parse(projectId, response.body());
    }

    private HttpResponse<String> send(String projectId) {
        String segment = URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/provision/" + segment + "/permissions/"))
                .header("Authorization", "Bearer " + requireToken(projectId))
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PermissionsUnavailableException("interrupted reading the permissions of " + projectId, e);
        } catch (IOException e) {
            throw new PermissionsUnavailableException("cannot reach the control plane for " + projectId, e);
        }
    }

    private String requireToken(String projectId) {
        try {
            return tokenSource.require();
        } catch (TokenUnavailableException e) {
            throw new PermissionsUnavailableException("no token to read the permissions of " + projectId, e);
        }
    }

    private PermissionSet parse(String projectId, String body) {
        PermissionSet permissions;
        try {
            permissions = PermissionSetParser.parse(MAPPER.readTree(body));
        } catch (IOException e) {
            throw new PermissionsUnavailableException("the permissions of " + projectId + " are not valid JSON", e);
        } catch (PermissionDocumentException e) {
            log.error("permissions_document_refused project={} reason={}", projectId, e.getMessage());
            Counter.builder(REFUSED_METRIC).tag(PROJECT_TAG, projectId).register(meters).increment();
            throw new RefusedDocumentException("the permissions of " + projectId + " were refused", e);
        }
        if (!projectId.equals(permissions.projectId())) {
            throw new PermissionsUnavailableException(
                    "asked for the permissions of " + projectId + " and got " + permissions.projectId());
        }
        return permissions;
    }
}
