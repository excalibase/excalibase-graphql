package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.function.Supplier;

/**
 * Reads the engine's own token settings ({@code app.auth.*}, environment {@code APP_AUTH_*}) and checks them with the
 * grammar of the document's {@code jwt:} section, so there is one set of rules. Only the names below exist: any other
 * {@code APP_AUTH_*} name or {@code app.auth.*} key refuses start, because a misspelt audience or claim path that
 * silently does nothing is worse than no start.
 */
public final class AuthSettingsReader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "APP_AUTH_";
    private static final String SECRET = "secret";
    private static final String SECRET_FILE = "secret-file";
    private static final String EXTRA = "claims.extra";

    /** Environment name to property key under {@code app.auth}. */
    private static final Map<String, String> ENV_TO_KEY = envToKey();

    public static final Set<String> ENV_NAMES = Set.copyOf(ENV_TO_KEY.keySet());
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    /** The property keys under {@code app.auth}, exactly as Micronaut holds them. */
    public static final Set<String> KEYS = Set.copyOf(ENV_TO_KEY.values());

    public static final String BOTH_REFUSAL = "app.auth.* and app.security.auth.* are both set: configure one token "
            + "source, not two";

    static final String HOSTED_REFUSAL = "app.auth.* (APP_AUTH_*) is refused in hosted mode "
            + "(app.security.rls.policy-url or app.security.multi-tenant.provisioning-url is set): one engine-wide "
            + "verifier would bypass the per-project audience check";

    private AuthSettingsReader() {
    }

    private static Map<String, String> envToKey() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("APP_AUTH_ISSUER", "issuer");
        names.put("APP_AUTH_JWKS_URL", "jwks-url");
        names.put("APP_AUTH_SECRET", SECRET);
        names.put("APP_AUTH_SECRET_FILE", SECRET_FILE);
        names.put("APP_AUTH_ALGORITHMS", "algorithms");
        names.put("APP_AUTH_AUDIENCE", "audience");
        names.put("APP_AUTH_CLOCK_SKEW_SECONDS", "clock-skew");
        names.put("APP_AUTH_CLAIMS_ROLE", "claims.role");
        names.put("APP_AUTH_CLAIMS_DEFAULT_ROLE", "claims.default-role");
        names.put("APP_AUTH_CLAIMS_USER_ID", "claims.user-id");
        names.put("APP_AUTH_CLAIMS_EXTRA", EXTRA);
        return Map.copyOf(names);
    }

    /** What start-up knows when it decides. Keys are relative to {@code app.auth}. */
    public record Inputs(boolean hosted, boolean jwtEnabled, boolean legacy, Collection<String> envNames,
                         Collection<String> propertyKeys, UnaryOperator<String> value) {
    }

    /**
     * The engine's token settings, or none. Refuses: an unknown name, any name in hosted mode, settings with
     * authentication off, settings beside the legacy ones.
     */
    public static EngineAuth resolve(Inputs inputs) {
        boolean present = anySet(inputs);
        if (inputs.hosted() && present) {
            throw new IllegalStateException(HOSTED_REFUSAL);
        }
        refuseUnknown(inputs.envNames(), inputs.propertyKeys());
        if (!present) {
            return EngineAuth.of(inputs.legacy());
        }
        if (!inputs.jwtEnabled()) {
            throw new IllegalStateException("app.auth.* is set but app.security.jwt-enabled is false: the settings "
                    + "would do nothing; remove them or turn authentication on");
        }
        if (inputs.legacy()) {
            throw new IllegalStateException(BOTH_REFUSAL);
        }
        return fromValues(inputs.value());
    }

    private static boolean anySet(Inputs inputs) {
        boolean unknown = inputs.envNames().stream().anyMatch(AuthSettingsReader::unknownEnvName);
        boolean unknownKey = inputs.propertyKeys().stream().anyMatch(AuthSettingsReader::unknownPropertyKey);
        boolean known = KEYS.stream().anyMatch(key -> !blank(inputs.value().apply(key)));
        return unknown || unknownKey || known;
    }

    /** The CLI's reading: the process environment, with the same names and checks. */
    public static EngineAuth fromEnv(Map<String, String> env) {
        refuseUnknown(env.keySet(), Set.of());
        Map<String, String> upper = new LinkedHashMap<>();
        env.forEach((name, value) -> upper.putIfAbsent(name.toUpperCase(Locale.ROOT), value));
        return fromValues(key -> {
            String name = ENV_TO_KEY.entrySet().stream().filter(entry -> entry.getValue().equals(key))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            return upper.get(name);
        });
    }

    /** @throws IllegalStateException a name that is not one of the engine's token settings */
    public static void refuseUnknown(Collection<String> envNames, Collection<String> propertyKeys) {
        for (String name : envNames) {
            if (unknownEnvName(name)) {
                throw new IllegalStateException(name + " is not an engine token setting; the names are: "
                        + String.join(", ", ENV_NAMES.stream().sorted().toList()));
            }
        }
        for (String key : propertyKeys) {
            if (unknownPropertyKey(key)) {
                throw new IllegalStateException("app.auth." + key + " is not an engine token setting; the keys are: "
                        + String.join(", ", KEYS.stream().sorted().toList()));
            }
        }
    }

    /** Micronaut binds an environment name in any case, so the prefix and the known names are compared upper-cased. */
    private static boolean unknownEnvName(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith(PREFIX) && !ENV_NAMES.contains(upper);
    }

    /**
     * What Micronaut reads under {@code app.auth} once the settings are declared in application.yml: any case, camel
     * case ({@code userId} is {@code user-id}) and {@code _} for {@code -}. It does not read a dot for a dash
     * ({@code jwks.url}: the declared {@code jwks-url} wins and stays empty) and never lets a dash or an underscore
     * stand in for the dot between {@code claims} and its field. Any other key is never read, so accepting it would
     * start cleanly and leave a default in place.
     */
    private static boolean unknownPropertyKey(String key) {
        return !KEYS.contains(canonical(key));
    }

    private static String canonical(String key) {
        return CAMEL_BOUNDARY.matcher(key).replaceAll("-").toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** @param value the setting by its key under {@code app.auth}; null or blank means not set */
    static EngineAuth fromValues(UnaryOperator<String> value) {
        if (KEYS.stream().allMatch(key -> blank(value.apply(key)))) {
            return EngineAuth.NONE;
        }
        try {
            return build(value);
        } catch (MetadataDocumentException e) {
            throw new IllegalStateException("app.auth: " + e.getMessage().replaceFirst("^jwt[.:]\\s*", ""), e);
        }
    }

    private static EngineAuth build(UnaryOperator<String> value) {
        boolean hasSecret = !blank(value.apply(SECRET));
        boolean hasFile = !blank(value.apply(SECRET_FILE));
        if (hasSecret && hasFile) {
            throw new IllegalStateException("app.auth: set APP_AUTH_SECRET or APP_AUTH_SECRET_FILE, not both");
        }
        byte[] secret = null;
        if (hasSecret) {
            secret = secret(() -> JwtKeyLoader.secretValue(value.apply(SECRET)), "APP_AUTH_SECRET");
        } else if (hasFile) {
            secret = secret(() -> JwtKeyLoader.secret("file:" + value.apply(SECRET_FILE).strip(), System::getenv),
                    "APP_AUTH_SECRET_FILE");
        }
        String source = secretSource(hasSecret, hasFile);
        JwtSettings settings = JwtSettingsParser.parseEngine(tree(value), source);
        return EngineAuth.engine(settings, secret);
    }

    private static byte[] secret(Supplier<byte[]> read, String name) {
        try {
            return read.get();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("app.auth: " + name + " must hold a secret of at least 32 bytes", e);
        }
    }

    private static String secretSource(boolean hasSecret, boolean hasFile) {
        if (hasSecret) {
            return "engine:APP_AUTH_SECRET";
        }
        return hasFile ? "engine:APP_AUTH_SECRET_FILE" : null;
    }

    private static ObjectNode tree(UnaryOperator<String> value) {
        ObjectNode node = JSON.createObjectNode();
        put(node, "issuer", value.apply("issuer"));
        put(node, "jwksUrl", value.apply("jwks-url"));
        list(node, "algorithms", value.apply("algorithms"));
        list(node, "audience", value.apply("audience"));
        if (!blank(value.apply("clock-skew"))) {
            node.put("clockSkew", seconds(value.apply("clock-skew")));
        }
        ObjectNode claims = JSON.createObjectNode();
        put(claims, "role", value.apply("claims.role"));
        put(claims, "defaultRole", value.apply("claims.default-role"));
        put(claims, "userId", value.apply("claims.user-id"));
        extra(claims, value.apply(EXTRA));
        if (!claims.isEmpty()) {
            node.set("claims", claims);
        }
        return node;
    }

    private static void put(ObjectNode node, String name, String text) {
        if (!blank(text)) {
            node.put(name, text.strip());
        }
    }

    private static void list(ObjectNode node, String name, String text) {
        if (blank(text)) {
            return;
        }
        ArrayNode array = node.putArray(name);
        for (String item : text.split(",", -1)) {
            array.add(item.strip());
        }
    }

    private static int seconds(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("app.auth: clockSkew: must be whole seconds from 0 to 300", e);
        }
    }

    private static void extra(ObjectNode claims, String text) {
        if (blank(text)) {
            return;
        }
        ObjectNode extra = claims.putObject("extra");
        for (String pair : List.of(text.split(",", -1))) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || equals == pair.length() - 1) {
                throw new IllegalStateException("app.auth: claims.extra: must be name=path pairs separated by commas");
            }
            extra.put(pair.substring(0, equals).strip(), pair.substring(equals + 1).strip());
        }
    }
}
