package io.github.excalibase.rls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * {@link PolicyProvider} that reads a project's RLS/CLS policies from the
 * provisioning API ({@code GET {base}/provision/{projectId}/rls-policies/} and
 * {@code .../column-policies/}) and caches them per project for {@code ttlMillis}.
 *
 * <p>On a fetch failure it serves the last good copy if one is cached, else it
 * throws {@link PolicyFetchException}. It must never return an empty list on
 * error: the engine reads empty as {@code UNRESTRICTED}, which would silently
 * disable RLS.
 */
public final class ProvisioningPolicyProvider implements PolicyProvider {

    private final HttpClient http;
    private final String baseUrl;
    private final String pat;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Cached<List<Policy>>> rowCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<ColumnPolicy>>> columnCache = new ConcurrentHashMap<>();

    private record Cached<T>(T value, long fetchedAt) {}

    public ProvisioningPolicyProvider(String baseUrl, String pat, long ttlMillis) {
        this(baseUrl, pat, ttlMillis, System::currentTimeMillis);
    }

    ProvisioningPolicyProvider(String baseUrl, String pat, long ttlMillis, LongSupplier clock) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.pat = Objects.requireNonNull(pat, "pat");
        this.ttlMillis = ttlMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public List<Policy> policiesFor(String projectId) {
        return cachedFetch(projectId, rowCache,
                "/provision/" + projectId + "/rls-policies/", this::parsePolicies);
    }

    @Override
    public List<ColumnPolicy> columnPoliciesFor(String projectId) {
        return cachedFetch(projectId, columnCache,
                "/provision/" + projectId + "/column-policies/", this::parseColumnPolicies);
    }

    /** Drops the cached policies for one project — the write side a NATS consumer calls. */
    public void evict(String projectId) {
        rowCache.remove(projectId);
        columnCache.remove(projectId);
    }

    private <T> List<T> cachedFetch(String projectId,
                                    Map<String, Cached<List<T>>> cache,
                                    String path,
                                    Function<JsonNode, List<T>> parser) {
        long nowMs = clock.getAsLong();
        Cached<List<T>> current = cache.get(projectId);
        if (current != null && nowMs - current.fetchedAt() < ttlMillis) {
            return current.value();
        }
        try {
            List<T> fresh = fetch(path, parser);
            cache.put(projectId, new Cached<>(fresh, nowMs));
            return fresh;
        } catch (PolicyFetchException e) {
            if (current != null) {
                return current.value();   // stale-while-error
            }
            throw e;                      // fail-closed: no policies to fall back on
        }
    }

    private <T> List<T> fetch(String path, Function<JsonNode, List<T>> parser) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + pat)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new PolicyFetchException(
                        "provisioning returned HTTP " + response.statusCode() + " for " + path);
            }
            return parser.apply(mapper.readTree(response.body()));
        } catch (java.io.IOException e) {
            throw new PolicyFetchException("failed to fetch policies from " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PolicyFetchException("interrupted fetching policies from " + path, e);
        }
    }

    // JSON shape is 1:1 with provisioning's domain/rls.go.

    private List<Policy> parsePolicies(JsonNode root) {
        List<Policy> out = new ArrayList<>();
        for (JsonNode node : arrayOf(root)) {
            out.add(new Policy(
                    text(node, "id"),
                    text(node, "name"),
                    text(node, "resource"),
                    PolicyEffect.valueOf(text(node, "effect")),
                    operations(node.get("operations")),
                    LogicOperator.valueOf(textOr(node, "ruleLogic", "AND")),
                    node.path("priority").asInt(0),
                    node.path("enabled").asBoolean(true),
                    rules(node.get("rules")),
                    assignments(node.get("assignments"))));
        }
        return List.copyOf(out);
    }

    private List<ColumnPolicy> parseColumnPolicies(JsonNode root) {
        List<ColumnPolicy> out = new ArrayList<>();
        for (JsonNode node : arrayOf(root)) {
            out.add(new ColumnPolicy(
                    text(node, "id"),
                    text(node, "name"),
                    text(node, "resource"),
                    stringSet(node.get("columns")),
                    operations(node.get("operations")),
                    MaskMode.valueOf(text(node, "mode")),
                    partialSpec(node.get("partialSpec")),
                    text(node, "customMaskerKey"),
                    node.path("priority").asInt(0),
                    node.path("enabled").asBoolean(true),
                    assignments(node.get("assignments"))));
        }
        return List.copyOf(out);
    }

    private static List<Rule> rules(JsonNode arr) {
        List<Rule> out = new ArrayList<>();
        for (JsonNode r : arrayOf(arr)) {
            out.add(new Rule(
                    text(r, "field"),
                    FieldType.valueOf(text(r, "fieldType")),
                    RuleOperator.valueOf(text(r, "operator")),
                    text(r, "value")));
        }
        return out;
    }

    private static List<Assignment> assignments(JsonNode arr) {
        List<Assignment> out = new ArrayList<>();
        for (JsonNode a : arrayOf(arr)) {
            out.add(new Assignment(TargetType.valueOf(text(a, "targetType")), text(a, "targetId")));
        }
        return out;
    }

    private static Set<Operation> operations(JsonNode arr) {
        Set<Operation> ops = new LinkedHashSet<>();
        for (JsonNode op : arrayOf(arr)) {
            ops.add(Operation.valueOf(op.asText()));
        }
        return ops;
    }

    private static Set<String> stringSet(JsonNode arr) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode s : arrayOf(arr)) {
            out.add(s.asText());
        }
        return out;
    }

    private static PartialMaskSpec partialSpec(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String kind = text(node, "kind");
        char maskChar = maskChar(node);
        return switch (kind) {
            case "KEEP_FIRST" -> new PartialMaskSpec.KeepFirst(node.path("n").asInt(), maskChar);
            case "KEEP_LAST" -> new PartialMaskSpec.KeepLast(node.path("n").asInt(), maskChar);
            case "KEEP_BOTH" -> new PartialMaskSpec.KeepBoth(
                    node.path("first").asInt(), node.path("last").asInt(), maskChar);
            case "MASK_RANGE" -> new PartialMaskSpec.MaskRange(
                    node.path("start").asInt(), node.path("end").asInt(), maskChar);
            case "SUBSTRING" -> new PartialMaskSpec.Substring(
                    node.path("start").asInt(), node.path("length").asInt(), maskChar);
            case "REGEX" -> new PartialMaskSpec.Regex(text(node, "pattern"), text(node, "replacement"));
            case null, default -> throw new PolicyFetchException("unknown partialSpec kind: " + kind);
        };
    }

    private static char maskChar(JsonNode node) {
        String s = text(node, "maskChar");
        return (s == null || s.isEmpty()) ? '*' : s.charAt(0);
    }

    private static Iterable<JsonNode> arrayOf(JsonNode node) {
        return (node != null && node.isArray()) ? node : List.of();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String v = text(node, field);
        return v == null ? fallback : v;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
