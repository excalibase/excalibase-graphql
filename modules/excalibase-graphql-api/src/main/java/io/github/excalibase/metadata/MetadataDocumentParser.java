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
    private static final Set<String> KEYS =
            Set.of("format", "projectId", "tables", "functions", "functionPermissions");
    private static final Set<String> RULE_KEYS = Set.of("tables", "functions", "functionPermissions");

    /** A repeated key would otherwise let the last one silently win. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final ReservedSchemas reserved;
    private final String expectedProjectId;

    /** @param expectedProjectId the deployment's project, or null to accept any (the CLI without a project) */
    public MetadataDocumentParser(ReservedSchemas reserved, String expectedProjectId) {
        this.reserved = reserved;
        this.expectedProjectId = expectedProjectId == null || expectedProjectId.isBlank() ? null : expectedProjectId;
    }

    /** @param version the engine's local version, given to the rules; a document cannot set its own */
    public PermissionSet parse(String text, long version) {
        try {
            return read(text, version);
        } catch (MetadataDocumentException e) {
            throw e;
        } catch (StackOverflowError e) {
            throw new MetadataDocumentException("document: is nested too deeply");
        } catch (RuntimeException e) {
            throw new MetadataDocumentException("document: cannot be read (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private PermissionSet read(String text, long version) {
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
        return permissions;
    }

    private static String unknownKey(String key) {
        return switch (key) {
            case "jwt" -> "jwt: jwt settings are not supported yet; token verification is still configured "
                    + "through the engine's own settings";
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
        JsonNode node = document.get("projectId");
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
        rules.put("projectId", projectId);
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
        permissions.tables().forEach(rule -> refuseIfReserved(rule.table(), "tables"));
        permissions.functions().forEach(function -> refuseIfReserved(function.function(), "functions"));
        permissions.functionPermissions().forEach(grant -> refuseIfReserved(grant.function(), "functionPermissions"));
    }

    private void refuseIfReserved(String qualifiedName, String section) {
        String schema = qualifiedName.substring(0, qualifiedName.indexOf('.'));
        if (reserved.isReserved(schema)) {
            throw new MetadataDocumentException(section + ": " + qualifiedName + " names the reserved schema \""
                    + schema + "\", which the engine never exposes");
        }
    }
}
