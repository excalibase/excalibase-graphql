package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.excalibase.security.Principal;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads and validates the {@code jwt:} section. Nothing here touches the network or the environment. */
final class JwtSettingsParser {

    static final Set<String> ASYMMETRIC = Set.of("RS256", "RS384", "RS512", "ES256", "ES384", "ES512",
            "PS256", "PS384", "PS512");
    static final Set<String> SYMMETRIC = Set.of("HS256", "HS384", "HS512");
    private static final Set<String> KEYS = Set.of("jwksUrl", "issuer", "secret", "algorithms", "audience",
            "claims", "clockSkew");
    private static final Set<String> CLAIM_KEYS = Set.of("role", "defaultRole", "userId", "extra");
    private static final Pattern SECRET_REF = Pattern.compile("^(env:[A-Za-z_]\\w*|file:\\S.*)$");
    private static final Pattern EXTRA_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,62}$");
    /** Names the engine fills itself; a document may not map a claim onto them. */
    private static final Set<String> RESERVED_NAMES = Set.of("role", "userid", "user-id", "project-id", "projectid",
            "email", "is-guest", "is_guest", "aal", "iss", "aud", "exp", "iat", "nbf", "jti", "token_use");
    private static final int MAX_PATH = 256;
    private static final int MAX_SKEW_SECONDS = 300;
    private static final int DEFAULT_SKEW_SECONDS = 30;
    private static final String ISSUER = "jwt.issuer";

    private JwtSettingsParser() {
    }

    static JwtSettings parse(JsonNode jwt) {
        if (!jwt.isObject()) {
            throw invalid("jwt: must be an object");
        }
        rejectUnknown(jwt, KEYS, "jwt");
        String secret = secretRef(jwt);
        String jwksUrl = text(jwt, "jwksUrl");
        String issuer = text(jwt, "issuer");
        requireOneKeySource(secret, jwksUrl, issuer);
        if (jwksUrl != null) {
            requireHttpsUrl(jwksUrl, "jwt.jwksUrl");
        } else if (secret == null) {
            requireHttpsUrl(issuer, ISSUER);
        }
        return new JwtSettings(jwksUrl, issuer, secret, algorithms(jwt, secret != null), audience(jwt),
                claims(jwt.get("claims")), clockSkew(jwt));
    }

    private static void requireOneKeySource(String secret, String jwksUrl, String issuer) {
        if (secret != null && jwksUrl != null) {
            throw invalid("jwt.secret: cannot be combined with jwksUrl, or an HMAC token could pass for a signed one");
        }
        if (secret == null && jwksUrl == null && issuer == null) {
            throw invalid("jwt: needs jwksUrl with issuer, issuer alone (OIDC discovery), or secret");
        }
        if (jwksUrl != null && issuer == null) {
            throw invalid(ISSUER + ": is required with jwksUrl, so a token from another issuer is refused");
        }
    }

    /** Plain http only to this machine or cluster; the value is never echoed, a URL can carry credentials. */
    static URI requireHttpsUrl(String value, String setting) {
        String message = setting + ": must be an https URL without user:password (plain http only to localhost, "
                + "loopback, *.svc or *.cluster.local)";
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean secure = scheme.equals("https") || scheme.equals("http") && uri.getHost() != null
                    && UrlLocation.isLocalOrClusterInternal(uri.getHost());
            if (!secure || uri.getHost() == null || uri.getUserInfo() != null) {
                throw invalid(message);
            }
            return uri;
        } catch (URISyntaxException _) {
            throw invalid(message);
        }
    }

    private static String secretRef(JsonNode jwt) {
        String secret = text(jwt, "secret");
        if (secret != null && !SECRET_REF.matcher(secret).matches()) {
            throw invalid("jwt.secret: must be a reference, env:NAME or file:/path, never the secret itself");
        }
        return secret;
    }

    private static List<String> algorithms(JsonNode jwt, boolean hmac) {
        Set<String> allowed = hmac ? SYMMETRIC : ASYMMETRIC;
        JsonNode node = jwt.get("algorithms");
        if (node == null) {
            return hmac ? List.of("HS256") : List.of("RS256", "ES256");
        }
        if (!node.isArray() || node.isEmpty()) {
            throw invalid("jwt.algorithms: must be a non-empty list");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode entry : node) {
            if (!entry.isTextual() || !allowed.contains(entry.asText())) {
                throw invalid("jwt.algorithms: only " + String.join(", ", allowed.stream().sorted().toList())
                        + " are accepted" + (hmac ? " with a secret" : " with a key set; HMAC and none are refused"));
            }
            names.add(entry.asText());
        }
        return List.copyOf(names);
    }

    private static List<String> audience(JsonNode jwt) {
        JsonNode node = jwt.get("audience");
        List<String> values = new ArrayList<>();
        if (node != null && node.isTextual()) {
            values.add(node.asText());
        } else if (node != null && node.isArray()) {
            node.forEach(entry -> values.add(entry.isTextual() ? entry.asText() : ""));
        }
        if (values.isEmpty() || values.stream().anyMatch(String::isBlank)) {
            throw invalid("jwt.audience: is required, a string or a list of strings; a token for another "
                    + "audience must be refused");
        }
        return List.copyOf(values);
    }

    private static JwtSettings.Claims claims(JsonNode node) {
        if (node == null) {
            return new JwtSettings.Claims("role", null, "sub", Map.of());
        }
        if (!node.isObject()) {
            throw invalid("jwt.claims: must be an object");
        }
        rejectUnknown(node, CLAIM_KEYS, "jwt.claims");
        String defaultRole = text(node, "defaultRole", "jwt.claims.defaultRole");
        if (defaultRole != null && (!Principal.isValidRoleName(defaultRole) || Principal.SERVICE.equals(defaultRole))) {
            throw invalid("jwt.claims.defaultRole: must be a role name other than service");
        }
        return new JwtSettings.Claims(path(node, "role", "role"), defaultRole, path(node, "userId", "sub"),
                extra(node.get("extra")));
    }

    private static Map<String, String> extra(JsonNode node) {
        if (node == null) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw invalid("jwt.claims.extra: must be a map of name to claim path");
        }
        Map<String, String> extra = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            String where = "jwt.claims.extra." + entry.getKey();
            if (!EXTRA_NAME.matcher(entry.getKey()).matches() || RESERVED_NAMES.contains(entry.getKey())) {
                throw invalid(where + ": the name must be lower-case letters, digits, - or _ and not one the "
                        + "engine fills itself (it becomes x-excalibase-<name>)");
            }
            extra.put(entry.getKey(), requirePath(entry.getValue(), where));
        });
        return Map.copyOf(extra);
    }

    private static Duration clockSkew(JsonNode jwt) {
        JsonNode node = jwt.get("clockSkew");
        if (node == null) {
            return Duration.ofSeconds(DEFAULT_SKEW_SECONDS);
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0
                || node.intValue() > MAX_SKEW_SECONDS) {
            throw invalid("jwt.clockSkew: must be whole seconds from 0 to " + MAX_SKEW_SECONDS);
        }
        return Duration.ofSeconds(node.intValue());
    }

    private static String path(JsonNode parent, String key, String fallback) {
        return parent.has(key) ? requirePath(parent.get(key), "jwt.claims." + key) : fallback;
    }

    private static String requirePath(JsonNode node, String where) {
        if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > MAX_PATH) {
            throw invalid(where + ": must be a non-empty claim path (up to " + MAX_PATH + " characters)");
        }
        return node.asText();
    }

    private static String text(JsonNode parent, String key) {
        return text(parent, key, "jwt." + key);
    }

    private static String text(JsonNode parent, String key, String where) {
        JsonNode node = parent.get(key);
        if (node == null) {
            return null;
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw invalid(where + ": must be a non-empty string");
        }
        return node.asText();
    }

    private static void rejectUnknown(JsonNode object, Set<String> known, String where) {
        object.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) {
                throw invalid(where + "." + name + ": unknown key");
            }
        });
    }

    private static MetadataDocumentException invalid(String message) {
        return new MetadataDocumentException(message);
    }
}
