package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * How the engine checks tokens, for the console's Auth page: where the settings come from and what they say, with the
 * secret only as {@code set} or {@code unset}, and how the document's own {@code jwt:} section stands beside them.
 * Never a secret, a reference to one, a length or a hash. Read-only.
 */
public final class AuthReport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JWT = "jwt";
    private static final String ABSENT = "absent";
    private static final String DIFFERENT = "different";
    private static final String IDENTICAL = "identical";
    private static final String DEPRECATED = "present-deprecated";
    private static final String CODE = "code";
    private static final String SET = "set";
    private static final String UNSET = "unset";
    private static final String NO_TOKEN_VERIFIER = "no_token_verifier";

    /** What the legacy {@code app.security.auth.*} settings hold, for a report that must name no secret. */
    public record Legacy(String jwksUrl, String issuer, boolean hmacSecret) {
    }

    private AuthReport() {
    }

    /**
     * @param engine      the engine's own settings, the legacy ones, or none
     * @param legacy      the legacy settings' values; read only when {@code engine} says legacy
     * @param served      the document being served, or null when none is
     * @param environment where the document's {@code env:} secret reference is read, to compare it with the engine's
     */
    public static Map<String, Object> of(EngineAuth engine, Legacy legacy, MetadataSource.Snapshot served,
                                         UnaryOperator<String> environment) {
        JwtSettings documentJwt = documentSection(served);
        String state = documentState(engine, documentJwt, served, environment);
        Map<String, Object> report = new LinkedHashMap<>();
        if (engine.engine()) {
            describe(report, "engine", engine.settings(), true);
        } else if (engine.legacy()) {
            legacy(report, legacy);
        } else if (documentJwt != null) {
            describe(report, "document", documentJwt, served.verifier() != null);
        } else {
            describe(report, "none", null, false);
        }
        report.put("documentJwt", state);
        report.put("warnings", warnings(engine, state, served));
        return report;
    }

    private static void describe(Map<String, Object> report, String source, JwtSettings settings, boolean keysLoaded) {
        report.put("source", source);
        report.put("issuer", settings == null ? null : settings.issuer());
        report.put("jwksUrl", settings == null ? null : address(settings.jwksUrl()));
        report.put("algorithms", settings == null ? List.of() : settings.algorithms());
        report.put("audience", settings == null ? List.of() : settings.audience());
        report.put("clockSkew", settings == null ? null : settings.clockSkew().toSeconds());
        report.put("claims", settings == null ? null : claims(settings.claims()));
        report.put("secret", settings != null && settings.secretRef() != null ? SET : UNSET);
        report.put("keysLoaded", keysLoaded);
    }

    private static void legacy(Map<String, Object> report, Legacy legacy) {
        report.put("source", "legacy-engine");
        report.put("issuer", legacy == null || legacy.issuer().isBlank() ? null : legacy.issuer());
        report.put("jwksUrl", legacy == null ? null : address(legacy.jwksUrl()));
        report.put("algorithms", List.of());
        report.put("audience", List.of());
        report.put("clockSkew", null);
        report.put("claims", null);
        report.put("secret", legacy != null && legacy.hmacSecret() ? SET : UNSET);
        report.put("keysLoaded", null);
    }

    private static Map<String, Object> claims(JwtSettings.Claims claims) {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("role", claims.role());
        shown.put("defaultRole", claims.defaultRole() == null ? "" : claims.defaultRole());
        shown.put("userId", claims.userId());
        shown.put("extra", Map.copyOf(claims.extra()));
        return shown;
    }

    /** A key set's address as the engine fetches it, without a query or fragment: those can carry a key. */
    static String address(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.strip());
            if (uri.getHost() == null) {
                return null;
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (URISyntaxException _) {
            return null;
        }
    }

    /** The served document as a tree, or null when none is served or it cannot be read as one. */
    private static JsonNode tree(MetadataSource.Snapshot served) {
        if (served == null || served.text() == null) {
            return null;
        }
        try {
            return ConfigText.read(served.text(), MAPPER);
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static JwtSettings documentSection(MetadataSource.Snapshot served) {
        JsonNode root = tree(served);
        try {
            return root != null && root.has(JWT) ? JwtSettingsParser.parse(root.get(JWT)) : null;
        } catch (MetadataDocumentException _) {
            return null;
        }
    }

    private static String documentState(EngineAuth engine, JwtSettings section, MetadataSource.Snapshot served,
                                        UnaryOperator<String> environment) {
        if (!hasJwtKey(served)) {
            return ABSENT;
        }
        if (!engine.engine()) {
            return engine.legacy() ? DIFFERENT : DEPRECATED;
        }
        if (section == null) {
            return DIFFERENT;
        }
        byte[] secret = null;
        try {
            secret = section.secretRef() == null ? null : JwtKeyLoader.secret(section.secretRef(), environment);
        } catch (IllegalStateException _) {
            return DIFFERENT;
        }
        return engine.differences(section, secret).isEmpty() ? IDENTICAL : DIFFERENT;
    }

    private static boolean hasJwtKey(MetadataSource.Snapshot served) {
        JsonNode root = tree(served);
        return root != null && root.has(JWT);
    }

    private static List<Map<String, String>> warnings(EngineAuth engine, String state, MetadataSource.Snapshot served) {
        List<Map<String, String>> codes = new ArrayList<>();
        if (DEPRECATED.equals(state)) {
            codes.add(Map.of(CODE, "jwt_deprecated"));
        } else if (IDENTICAL.equals(state)) {
            codes.add(Map.of(CODE, "jwt_identical_remove_it"));
        }
        boolean verifies = engine.configured() || served != null && served.verifier() != null;
        if (!verifies && served != null && served.permissions().tables().stream().anyMatch(rule -> !"anon".equals(rule.role()))) {
            codes.add(Map.of(CODE, NO_TOKEN_VERIFIER));
        }
        return codes;
    }
}
