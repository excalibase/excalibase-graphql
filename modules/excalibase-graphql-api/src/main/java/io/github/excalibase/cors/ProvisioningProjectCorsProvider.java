package io.github.excalibase.cors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.security.TokenFileSource;
import io.github.excalibase.security.TokenUnavailableException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * {@link ProjectCorsProvider} that reads a project's allowlist from the
 * provisioning API ({@code GET {base}/projects/{projectId}/info}, field
 * {@code corsAllowedOrigins}) and caches it per project for {@code ttlMillis}.
 *
 * <p>The service token is read from the shared {@link TokenFileSource} on every
 * fetch, because the platform rotates it in place; capturing it once left the
 * provider authenticating with a stale or empty bearer after the first rotation.
 *
 * <p>Same contract as the RLS policy provider: on a fetch failure it serves the
 * last good copy if one is cached, else it throws {@link CorsOriginsFetchException}.
 * It never returns an empty list on error — the caller reads empty as "this
 * project allows no origin", which is a decision only provisioning may make.
 */
public final class ProvisioningProjectCorsProvider implements ProjectCorsProvider {

    private static final String ORIGINS_FIELD = "corsAllowedOrigins";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http;
    private final String baseUrl;
    private final TokenFileSource tokenSource;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(List<String> origins, long fetchedAt) {}

    public ProvisioningProjectCorsProvider(String baseUrl, String pat, long ttlMillis) {
        this(baseUrl, new TokenFileSource(null, Objects.requireNonNull(pat, "pat")), ttlMillis);
    }

    public ProvisioningProjectCorsProvider(String baseUrl, TokenFileSource tokenSource, long ttlMillis) {
        this(baseUrl, tokenSource, ttlMillis, System::currentTimeMillis);
    }

    ProvisioningProjectCorsProvider(String baseUrl, String pat, long ttlMillis, LongSupplier clock) {
        this(baseUrl, new TokenFileSource(null, Objects.requireNonNull(pat, "pat")), ttlMillis, clock);
    }

    ProvisioningProjectCorsProvider(String baseUrl, TokenFileSource tokenSource, long ttlMillis, LongSupplier clock) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
        this.ttlMillis = ttlMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public List<String> originsFor(String projectId) {
        long nowMs = clock.getAsLong();
        Cached current = cache.get(projectId);
        if (current != null && nowMs - current.fetchedAt() < ttlMillis) {
            return current.origins();
        }
        try {
            List<String> fresh = fetch(projectId);
            cache.put(projectId, new Cached(fresh, nowMs));
            return fresh;
        } catch (CorsOriginsFetchException e) {
            if (current != null) {
                return current.origins();   // stale-while-error
            }
            throw e;                        // fail-closed: nothing to fall back on
        }
    }

    /** Drops the cached list for one project so the next read re-fetches. */
    public void evict(String projectId) {
        cache.remove(projectId);
    }

    private List<String> fetch(String projectId) {
        String path = "/projects/" + projectId + "/info";
        String token;
        try {
            token = tokenSource.require();
        } catch (TokenUnavailableException e) {
            throw new CorsOriginsFetchException("cannot authenticate to provisioning for " + path, e);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new CorsOriginsFetchException(
                        "provisioning returned HTTP " + response.statusCode() + " for " + path);
            }
            return parseOrigins(mapper.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CorsOriginsFetchException("failed to fetch cors origins from " + path, e);
        } catch (IOException e) {
            throw new CorsOriginsFetchException("failed to fetch cors origins from " + path, e);
        }
    }

    /** A missing or null field is "no origins" — the JSON shape is 1:1 with provisioning's ProjectInfo. */
    private static List<String> parseOrigins(JsonNode root) {
        JsonNode field = root.get(ORIGINS_FIELD);
        if (field == null || !field.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(field.size());
        for (JsonNode origin : field) {
            out.add(origin.asText());
        }
        return List.copyOf(out);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
