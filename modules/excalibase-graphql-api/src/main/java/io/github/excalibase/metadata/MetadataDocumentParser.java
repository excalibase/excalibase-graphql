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

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Reads the metadata document: a small header ({@code format}, {@code projectId}) around the permission rules, which go to {@link PermissionSetParser} untouched so the rule grammar
 * exists once. The engine at load and {@code excalibase validate} both come through here, so they cannot
 * disagree. Any problem refuses the whole document.
 */
public final class MetadataDocumentParser {

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

    private final ReservedSchemas reserved;
    private final String expectedProjectId;

    /** The verifier last built and the settings it came from; a rules-only edit reuses it instead of refetching. */
    private JwtSettings builtFor;
    private TokenVerifier built;
    private final boolean envJwtConfigured;

    /** @param expectedProjectId the deployment's project, or null to accept any (the CLI without a project) */
    public MetadataDocumentParser(ReservedSchemas reserved, String expectedProjectId) {
        this(reserved, expectedProjectId, false);
    }

    /**
     * @param envJwtConfigured the engine has token settings of its own; a document {@code jwt:} section is then
     *                         refused, so start-up and every reload agree whatever the provider is doing
     */
    public MetadataDocumentParser(ReservedSchemas reserved, String expectedProjectId, boolean envJwtConfigured) {
        this.envJwtConfigured = envJwtConfigured;
        this.reserved = reserved;
        this.expectedProjectId = expectedProjectId == null || expectedProjectId.isBlank() ? null : expectedProjectId;
    }

    /** The rules and the JWT settings, validated; nothing is fetched. */
    private record Parsed(PermissionSet permissions, JwtSettings jwt) {
    }

    /**
     * Validates the document without reaching anything outside it: what {@code excalibase validate} runs.
     *
     * @param version the engine's local version, given to the rules; a document cannot set its own
     */
    public PermissionSet parse(String text, long version) {
        return validated(text, version).permissions();
    }

    /**
     * What the engine serves: the validated document, with the verifier its {@code jwt:} section describes. The
     * provider's keys are fetched here, so a document that cannot be fully applied is not swapped in.
     *
     * @throws IllegalStateException the document is valid but the keys or secret cannot be had right now
     */
    public synchronized MetadataDocument load(String text, long version) {
        Parsed parsed = validated(text, version);
        return new MetadataDocument(parsed.permissions(), verifierFor(parsed.jwt()));
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

    private Parsed validated(String text, long version) {
        try {
            return read(text, version);
        } catch (MetadataDocumentException e) {
            throw e;
        } catch (StackOverflowError _) {
            throw new MetadataDocumentException("document: is nested too deeply");
        } catch (RuntimeException e) {
            throw new MetadataDocumentException("document: cannot be read (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private Parsed read(String text, long version) {
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
        if (document.has(JWT) && envJwtConfigured) {
            throw new MetadataDocumentException("jwt: the engine already has token settings of its own "
                    + "(app.security.auth.*); keep them in one place, there or in the document");
        }
        return new Parsed(permissions, document.has(JWT) ? JwtSettingsParser.parse(document.get(JWT)) : null);
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
