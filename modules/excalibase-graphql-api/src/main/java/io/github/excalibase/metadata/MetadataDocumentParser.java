package io.github.excalibase.metadata;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.excalibase.permissions.PermissionDocumentException;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionSetParser;
import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.security.TokenVerifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Reads the metadata document: a small header ({@code format}, {@code projectId}) around the permission rules, which go to {@link PermissionSetParser} untouched so the rule grammar
 * exists once. The engine at load and {@code excalibase validate} both come through here, so they cannot
 * disagree. Any problem refuses the whole document.
 */
public final class MetadataDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(MetadataDocumentParser.class);
    public static final int MAX_BYTES = 1024 * 1024;
    static final int FORMAT = 1;
    private static final String PROJECT_ID = "projectId";
    private static final String TABLES = "tables";
    private static final String FUNCTIONS = "functions";
    private static final String FUNCTION_PERMISSIONS = "functionPermissions";
    private static final String JWT = "jwt";
    private static final Set<String> KEYS = Set.of("format", PROJECT_ID, JWT, TABLES, FUNCTIONS, FUNCTION_PERMISSIONS);
    private static final Set<String> RULE_KEYS = Set.of(TABLES, FUNCTIONS, FUNCTION_PERMISSIONS);

    /** A repeated key would otherwise let the last one silently win. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** What a document is refused with when its keys or secret cannot be loaded, on a save and in {@link #check}. */
    public static final String KEYS_UNAVAILABLE = "jwt: the signing keys or the secret cannot be loaded; check the "
            + "secret reference, or the JWKS and discovery URLs";

    private static final String NO_VERIFIER_NOTE = "no_token_verifier";
    private static final String DIFFERS = "jwt: the engine already has token settings of its own (app.auth.*) and this "
            + "section differs; keep them in one place, there or in the document, or make them identical to move them";

    private final ReservedSchemas reserved;
    private final String expectedProjectId;
    private final EngineAuth engineAuth;

    /** The verifier last built and the settings it came from; a rules-only edit reuses it instead of refetching. */
    private JwtSettings builtFor;
    private TokenVerifier built;

    /** @param expectedProjectId the deployment's project, or null to accept any (the CLI without a project) */
    public MetadataDocumentParser(ReservedSchemas reserved, String expectedProjectId) {
        this(reserved, expectedProjectId, EngineAuth.NONE);
    }

    /** @param envJwtConfigured the engine has the legacy {@code app.security.auth.*} settings */
    public MetadataDocumentParser(ReservedSchemas reserved, String expectedProjectId, boolean envJwtConfigured) {
        this(reserved, expectedProjectId, EngineAuth.of(envJwtConfigured));
    }

    /**
     * @param engineAuth the engine's own token settings; a document {@code jwt:} section is then refused (legacy
     *                   settings always, {@code app.auth.*} unless the section is identical), so start-up and every
     *                   reload agree whatever the provider is doing
     */
    public MetadataDocumentParser(ReservedSchemas reserved, String expectedProjectId, EngineAuth engineAuth) {
        this.engineAuth = engineAuth;
        this.reserved = reserved;
        this.expectedProjectId = expectedProjectId == null || expectedProjectId.isBlank() ? null : expectedProjectId;
    }

    /** The rules and the JWT settings, validated; nothing is fetched. */
    private record Parsed(PermissionSet permissions, JwtSettings jwt, List<Note> notes) {
    }

    /** A thing worth telling the operator that does not make the document wrong, by a stable code. */
    public record Note(String code, String message) {
    }

    /** @param notes deprecation, "identical, remove it" and "nothing verifies a token" */
    public record Checked(PermissionSet permissions, List<Note> notes) {
    }

    /**
     * Validates the document without reaching anything outside it: what {@code excalibase validate} runs.
     *
     * @param version the engine's local version, given to the rules; a document cannot set its own
     */
    public PermissionSet parse(String text, long version) {
        return validated(text, version, System::getenv).permissions();
    }

    /**
     * {@link #parse} plus the one check of the {@code jwt:} section that needs the outside: a secret reference must
     * resolve to a long enough secret, the same read the engine makes. A key set is still not fetched.
     *
     * @param environment where an {@code env:} reference is read from
     * @throws MetadataDocumentException the document is invalid or its secret cannot be loaded; never holds the secret
     */
    public PermissionSet check(String text, long version, UnaryOperator<String> environment) {
        return checkWithNotes(text, version, environment).permissions();
    }

    /** {@link #check} with the notes that go beside a valid document. */
    public Checked checkWithNotes(String text, long version, UnaryOperator<String> environment) {
        Parsed parsed = validated(text, version, environment);
        if (parsed.jwt() != null && parsed.jwt().secretRef() != null) {
            try {
                JwtKeyLoader.secret(parsed.jwt().secretRef(), environment);
            } catch (IllegalStateException e) {
                throw new MetadataDocumentException(KEYS_UNAVAILABLE, e);
            }
        }
        return new Checked(parsed.permissions(), parsed.notes());
    }

    /**
     * What the engine serves: the validated document, with the verifier its {@code jwt:} section describes. The
     * provider's keys are fetched here, so a document that cannot be fully applied is not swapped in.
     *
     * @throws IllegalStateException the document is valid but the keys or secret cannot be had right now
     */
    public synchronized MetadataDocument load(String text, long version) {
        Parsed parsed = validated(text, version, System::getenv);
        parsed.notes().stream().filter(note -> !NO_VERIFIER_NOTE.equals(note.code()))
                .forEach(note -> log.warn("{} version={}", logName(note), version));
        return new MetadataDocument(parsed.permissions(), verifierFor(parsed.jwt()));
    }

    /** The line operators grep for: {@code metadata_jwt_identical}, {@code metadata_jwt_deprecated}. */
    private static String logName(Note note) {
        return "metadata_" + note.code().replace("_remove_it", "");
    }

    /** Unchanged settings keep the served verifier (a rotated secret is picked up when the section changes). */
    private TokenVerifier verifierFor(JwtSettings jwt) {
        if (jwt == null) {
            builtFor = null;
            built = null;
        } else if (!jwt.equals(builtFor)) {
            built = DocumentJwtVerifier.create(jwt);
            builtFor = jwt;
        }
        return built;
    }

    private Parsed validated(String text, long version, UnaryOperator<String> environment) {
        try {
            return read(text, version, environment);
        } catch (MetadataDocumentException e) {
            throw e;
        } catch (StackOverflowError _) {
            throw new MetadataDocumentException("document: is nested too deeply");
        } catch (RuntimeException e) {
            throw new MetadataDocumentException("document: cannot be read (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private Parsed read(String text, long version, UnaryOperator<String> environment) {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new MetadataDocumentException("document is larger than 1 MiB");
        }
        JsonNode root = ConfigText.read(text, JSON);
        if (root == null || !root.isObject()) {
            throw new MetadataDocumentException("document: must be an object");
        }
        ObjectNode document = (ObjectNode) root;
        document.fieldNames().forEachRemaining(key -> {
            if (!KEYS.contains(key)) {
                throw new MetadataDocumentException(unknownKey(key));
            }
        });
        requireFormat(document);
        PermissionSet permissions = rules(document, projectId(document), version);
        refuseReserved(permissions);
        return withJwt(document, permissions, environment);
    }

    /** The section decides the verifier, or is refused, or is identical to the engine's and adds nothing. */
    private Parsed withJwt(ObjectNode document, PermissionSet permissions, UnaryOperator<String> environment) {
        List<Note> notes = new ArrayList<>();
        JwtSettings jwt = null;
        if (document.has(JWT)) {
            if (engineAuth.legacy()) {
                throw new MetadataDocumentException("jwt: the engine already has token settings of its own "
                        + "(app.security.auth.*); keep them in one place, there or in the document");
            }
            if (engineAuth.engine()) {
                requireIdentical(document.get(JWT), environment);
                notes.add(new Note("jwt_identical_remove_it", "The jwt: section says the same as the engine's own "
                        + "token settings (app.auth.*). You can remove it from the document."));
            } else {
                jwt = JwtSettingsParser.parse(document.get(JWT));
                notes.add(new Note("jwt_deprecated", "The jwt: section in the document is deprecated: move it to the "
                        + "engine's token settings (APP_AUTH_*)."));
            }
        } else if (!engineAuth.configured() && hasRulesBesidesAnon(permissions)) {
            notes.add(new Note(NO_VERIFIER_NOTE, "The document has rules for a role other than anon, but no token "
                    + "verifier is configured (app.auth.*, app.security.auth.* or a jwt: section): every token is "
                    + "rejected."));
        }
        return new Parsed(permissions, jwt, List.copyOf(notes));
    }

    private static boolean hasRulesBesidesAnon(PermissionSet permissions) {
        return permissions.tables().stream().anyMatch(rule -> !"anon".equals(rule.role()));
    }

    /** Never builds a verifier or fetches keys: it only proves the section says what the engine already has. */
    private void requireIdentical(JsonNode section, UnaryOperator<String> environment) {
        JwtSettings document = JwtSettingsParser.parse(section);
        byte[] secret = null;
        if (document.secretRef() != null) {
            try {
                secret = JwtKeyLoader.secret(document.secretRef(), environment);
            } catch (IllegalStateException e) {
                throw new MetadataDocumentException(DIFFERS + ": its secret cannot be resolved to compare it");
            }
        }
        String differences = engineAuth.differences(document, secret);
        if (!differences.isEmpty()) {
            throw new MetadataDocumentException(DIFFERS + " in: " + differences);
        }
    }

    private static String unknownKey(String key) {
        return switch (key) {
            case "version" -> "document: unknown key \"version\" (the version comes from the source)";
            default -> "document: unknown key \"" + key + "\"";
        };
    }

    private static void requireFormat(ObjectNode document) {
        JsonNode format = document.get("format");
        if (format == null || !format.isIntegralNumber() || !format.canConvertToInt() || format.intValue() != FORMAT) {
            throw new MetadataDocumentException("format: must be " + FORMAT);
        }
    }

    private String projectId(ObjectNode document) {
        JsonNode node = document.get(PROJECT_ID);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new MetadataDocumentException("projectId: must be a non-empty string");
        }
        if (expectedProjectId != null && !expectedProjectId.equals(node.asText())) {
            throw new MetadataDocumentException("projectId: the document is for \"" + node.asText()
                    + "\" but this deployment serves \"" + expectedProjectId + "\"");
        }
        return node.asText();
    }

    private static PermissionSet rules(ObjectNode document, String projectId, long version) {
        ObjectNode rules = JSON.createObjectNode();
        rules.put(PROJECT_ID, projectId);
        rules.put("version", version);
        for (String key : RULE_KEYS) {
            rules.set(key, document.has(key) ? document.get(key) : JSON.createArrayNode());
        }
        try {
            return PermissionSetParser.parse(rules);
        } catch (PermissionDocumentException e) {
            throw new MetadataDocumentException(e.getMessage().replaceFirst("^document\\.", ""), e);
        }
    }

    private void refuseReserved(PermissionSet permissions) {
        permissions.tables().forEach(rule -> refuseIfReserved(rule.table(), TABLES));
        permissions.functions().forEach(function -> refuseIfReserved(function.function(), FUNCTIONS));
        permissions.functionPermissions().forEach(grant -> refuseIfReserved(grant.function(), FUNCTION_PERMISSIONS));
    }

    private void refuseIfReserved(String qualifiedName, String section) {
        String schema = qualifiedName.substring(0, qualifiedName.indexOf('.'));
        if (reserved.isReserved(schema)) {
            throw new MetadataDocumentException(section + ": " + qualifiedName + " names the reserved schema \""
                    + schema + "\", which the engine never exposes");
        }
    }
}
