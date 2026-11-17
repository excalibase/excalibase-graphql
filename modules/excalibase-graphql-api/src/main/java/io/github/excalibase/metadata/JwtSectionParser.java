package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the shape of the {@code jwt:} section. Phase 1 reads it; nothing applies it yet. */
final class JwtSectionParser {

    private static final Set<String> KEYS =
            Set.of("issuer", "audience", "jwksUrl", "algorithms", "sharedSecretFrom", "claims");
    private static final Set<String> CLAIM_KEYS = Set.of("role", "defaultRole", "userId", "extra");
    private static final Set<String> ALGORITHMS =
            Set.of("RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "HS256");
    private static final Pattern REFERENCE = Pattern.compile("^(env|file):\\S+$");
    private static final Pattern EXTRA_NAME = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");

    private JwtSectionParser() {
    }

    static Optional<JwtSection> parse(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        object(node, "jwt", KEYS);
        String jwksUrl = optionalText(node, "jwksUrl", "jwt");
        String secret = optionalText(node, "sharedSecretFrom", "jwt");
        if (jwksUrl != null && secret != null) {
            throw fail("jwt: set either jwksUrl or sharedSecretFrom, not both");
        }
        if (secret != null && !REFERENCE.matcher(secret).matches()) {
            throw fail("jwt.sharedSecretFrom: must be a reference (env:NAME or file:/path), never the secret itself");
        }
        return Optional.of(new JwtSection(optionalText(node, "issuer", "jwt"), optionalText(node, "audience", "jwt"),
                jwksUrl, algorithms(node.get("algorithms")), secret, claims(node.get("claims"))));
    }

    private static List<String> algorithms(JsonNode node) {
        List<String> algorithms = new ArrayList<>();
        if (node == null) {
            return algorithms;
        }
        if (!node.isArray()) {
            throw fail("jwt.algorithms: must be an array");
        }
        for (JsonNode algorithm : node) {
            if (!algorithm.isTextual() || !ALGORITHMS.contains(algorithm.asText())) {
                throw fail("jwt.algorithms: each must be one of " + ALGORITHMS.stream().sorted().toList());
            }
            algorithms.add(algorithm.asText());
        }
        return List.copyOf(algorithms);
    }

    private static JwtSection.Claims claims(JsonNode node) {
        if (node == null) {
            return new JwtSection.Claims(null, null, null, Map.of());
        }
        object(node, "jwt.claims", CLAIM_KEYS);
        return new JwtSection.Claims(optionalText(node, "role", "jwt.claims"),
                optionalText(node, "defaultRole", "jwt.claims"), optionalText(node, "userId", "jwt.claims"),
                extra(node.get("extra")));
    }

    private static Map<String, String> extra(JsonNode node) {
        Map<String, String> extra = new LinkedHashMap<>();
        if (node == null) {
            return extra;
        }
        object(node, "jwt.claims.extra", null);
        node.properties().forEach(entry -> {
            if (!EXTRA_NAME.matcher(entry.getKey()).matches() || !entry.getValue().isTextual()) {
                throw fail("jwt.claims.extra." + entry.getKey() + ": a lower-case name mapped to a claim path");
            }
            extra.put(entry.getKey(), entry.getValue().asText());
        });
        return Map.copyOf(extra);
    }

    private static void object(JsonNode node, String path, Set<String> allowed) {
        if (!node.isObject()) {
            throw fail(path + ": must be an object");
        }
        if (allowed == null) {
            return;
        }
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) {
                throw fail(path + ": unknown key \"" + key + "\"");
            }
        });
    }

    private static String optionalText(JsonNode parent, String key, String path) {
        JsonNode node = parent.get(key);
        if (node == null) {
            return null;
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw fail(path + "." + key + ": must be a non-empty string");
        }
        return node.asText();
    }

    private static MetadataDocumentException fail(String message) {
        return new MetadataDocumentException(message);
    }
}
