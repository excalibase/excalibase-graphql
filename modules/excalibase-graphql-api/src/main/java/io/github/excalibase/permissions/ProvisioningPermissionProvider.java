package io.github.excalibase.permissions;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.excalibase.security.TokenFileSource;
import io.github.excalibase.security.TokenUnavailableException;
import io.github.excalibase.security.UnknownProjectException;
import io.micrometer.core.instrument.Counter;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Reads a project's permission document from the control plane
 * ({@code GET {base}/provision/{projectId}/permissions/}) and caches it per project for the policy TTL.
 *
 * <ul>
 *   <li>404: the project does not exist; its cached copy is dropped and nothing is cached.</li>
 *   <li>The control plane cannot be read: the last good copy is served if there is one, else
 *       {@link PermissionsUnavailableException}.</li>
 *   <li>A document that breaks the grammar is refused, never cached and never partly applied. The last
 *       good copy stands in for at most one TTL from the first refusal; after that the project is
 *       unavailable until a valid document arrives. Every refusal is logged and counted.</li>
 * </ul>
 */
public final class ProvisioningPermissionProvider implements PermissionProvider {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningPermissionProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Counter of refused documents, tagged by project; Prometheus shows it with a {@code _total} suffix. */
    public static final String REFUSED_METRIC = "excalibase.permissions.document.refused";

    /** A repeated key would otherwise let the last one silently win. */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;
    private final TokenFileSource tokenSource;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final MeterRegistry meters;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> firstRefusalAt = new ConcurrentHashMap<>();

    private record Cached(PermissionSet permissions, long fetchedAt) {}

    /** A document the control plane served but the grammar refused. */
    private static final class RefusedDocumentException extends PermissionsUnavailableException {
        RefusedDocumentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ProvisioningPermissionProvider(String baseUrl, TokenFileSource tokenSource, long ttlMillis,
                                          MeterRegistry meters) {
        this(baseUrl, tokenSource, ttlMillis, System::currentTimeMillis, meters);
    }

    ProvisioningPermissionProvider(String baseUrl, TokenFileSource tokenSource, long ttlMillis, LongSupplier clock,
                                   MeterRegistry meters) {
        String base = Objects.requireNonNull(baseUrl, "baseUrl");
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
        this.ttlMillis = ttlMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    @Override
    public PermissionSet permissionsFor(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is required");
        }
        long nowMs = clock.getAsLong();
        Cached current = cache.get(projectId);
        if (current != null && nowMs - current.fetchedAt() < ttlMillis) {
            return current.permissions();
        }
        try {
            PermissionSet fresh = fetch(projectId);
            cache.put(projectId, new Cached(fresh, nowMs));
            firstRefusalAt.remove(projectId);
            return fresh;
        } catch (UnknownProjectException e) {
            evict(projectId);
            throw e;
        } catch (RefusedDocumentException e) {
            return withinRefusalWindow(projectId, current, nowMs, e);
        } catch (PermissionsUnavailableException e) {
            if (current == null) {
                throw e;
            }
            log.warn("permissions_stale project={} version={} reason={}",
                    projectId, current.permissions().version(), e.getMessage());
            return current.permissions();
        }
    }

    /** The last good copy while the first refusal is younger than one TTL; unavailable after. */
    private PermissionSet withinRefusalWindow(String projectId, Cached current, long nowMs,
                                              RefusedDocumentException refusal) {
        long since = firstRefusalAt.computeIfAbsent(projectId, ignored -> nowMs);
        if (current == null || nowMs - since >= ttlMillis) {
            throw refusal;
        }
        log.warn("permissions_stale project={} version={} reason=document_refused",
                projectId, current.permissions().version());
        return current.permissions();
    }

    @Override
    public void evict(String projectId) {
        cache.remove(projectId);
        firstRefusalAt.remove(projectId);
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
            Counter.builder(REFUSED_METRIC).tag("project", projectId).register(meters).increment();
            throw new RefusedDocumentException("the permissions of " + projectId + " were refused", e);
        }
        if (!projectId.equals(permissions.projectId())) {
            throw new PermissionsUnavailableException(
                    "asked for the permissions of " + projectId + " and got " + permissions.projectId());
        }
        return permissions;
    }
}
