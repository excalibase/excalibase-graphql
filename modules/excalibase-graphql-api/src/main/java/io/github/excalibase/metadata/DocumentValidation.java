package io.github.excalibase.metadata;

import io.github.excalibase.access.RuleProblem;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.schema.ReservedSchemas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Checks a posted document with the parser the engine loads with, the same reserved schemas and project, and the same
 * rule about a {@code jwt:} section beside the engine's own token settings. It only reads: nothing is fetched,
 * swapped in or written, so what the engine serves is the same before and after.
 */
public final class DocumentValidation {

    private static final Logger log = LoggerFactory.getLogger(DocumentValidation.class);
    private static final ObjectMapper TREE_MAPPER = new ObjectMapper();

    private DocumentValidation() {
    }

    /**
     * A rule the database does not fit. The document is still valid and saves: tables may be created after the
     * document, but until then the engine drops the rule and the role holds less than the document says.
     */
    public record Warning(String code, String role, String table, String operation, String message) {

        static Warning of(RuleProblem problem) {
            return new Warning(problem.code(), problem.role(), problem.table(), problem.operation(), message(problem));
        }

        private static String message(RuleProblem problem) {
            String who = "role `" + problem.role() + "`";
            return switch (problem.code()) {
                case "unknown_table" -> "Table `" + problem.table() + "` does not exist in the database yet. The engine "
                        + "ignores the rules for it, so " + who + " has no access to it until it exists.";
                case "needs_select" -> "The " + problem.operation() + " rule on `" + problem.table() + "` needs a "
                        + "readable select rule beside it. The engine drops it, so " + who + " cannot "
                        + problem.operation() + " there until the select rule is fixed.";
                default -> "The " + problem.operation() + " rule of " + who + " on `" + problem.table() + "` does not "
                        + "fit the database (" + problem.reason() + "). The engine drops it, so " + who + " cannot "
                        + problem.operation() + " there until the column exists or the rule is fixed.";
            };
        }
    }

    /**
     * @param tables   rules read, {@code functions} tracked functions; both 0 when the document is refused
     * @param warnings what the database does not fit; empty when it fits or the schema is not known
     */
    public record Result(boolean valid, List<String> errors, int tables, int functions, List<Warning> warnings) {

        public Result(boolean valid, List<String> errors, int tables, int functions) {
            this(valid, errors, tables, functions, List.of());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("valid", valid);
            if (valid) {
                map.put("tables", tables);
                map.put("functions", functions);
                if (!warnings.isEmpty()) {
                    map.put("warnings", warnings);
                }
            } else {
                map.put("errors", errors);
            }
            return map;
        }
    }

    public static Result validate(byte[] body, ReservedSchemas reserved, String projectId, boolean envJwtConfigured) {
        return validate(body, reserved, projectId, envJwtConfigured, System::getenv);
    }

    public static Result validate(byte[] body, ReservedSchemas reserved, String projectId, boolean envJwtConfigured,
                                  UnaryOperator<String> environment) {
        return validate(body, reserved, projectId, envJwtConfigured, environment, null);
    }

    /**
     * @param environment where an {@code env:} secret reference is read, as the engine reads it
     * @param schemaCheck lists the rules the live schema does not fit, or null when the schema is not known; it can
     *                    only add warnings, so a failing check never refuses a document
     */
    public static Result validate(byte[] body, ReservedSchemas reserved, String projectId, boolean envJwtConfigured,
                                  UnaryOperator<String> environment,
                                  Function<PermissionSet, List<RuleProblem>> schemaCheck) {
        if (body == null || body.length == 0) {
            return refused("the document is empty");
        }
        if (body.length > MetadataDocumentParser.MAX_BYTES) {
            return refused("the document is too large: at most " + MetadataDocumentParser.MAX_BYTES + " bytes");
        }
        String text;
        try {
            text = FileMetadataSource.decodeUtf8(body);
        } catch (IOException _) {
            return refused("the document is not UTF-8 text");
        }
        try {
            PermissionSet permissions = new MetadataDocumentParser(reserved, projectId, envJwtConfigured)
                    .check(text, 1, environment);
            return new Result(true, List.of(), permissions.tables().size(), permissions.functions().size(),
                    warnings(permissions, schemaCheck));
        } catch (MetadataDocumentException e) {
            return refused(e.getMessage());
        }
    }

    /**
     * The document as a tree of maps, lists and scalars, so a client that cannot read YAML edits the same shape the
     * engine does. Only a document that validates has one; an invalid one answers with its errors, as {@code validate}.
     */
    public static Map<String, Object> tree(byte[] body, ReservedSchemas reserved, String projectId,
                                           boolean envJwtConfigured) {
        return tree(body, reserved, projectId, envJwtConfigured, null);
    }

    public static Map<String, Object> tree(byte[] body, ReservedSchemas reserved, String projectId,
                                           boolean envJwtConfigured,
                                           Function<PermissionSet, List<RuleProblem>> schemaCheck) {
        Result checked = validate(body, reserved, projectId, envJwtConfigured, System::getenv, schemaCheck);
        Map<String, Object> answer = checked.toMap();
        if (checked.valid()) {
            JsonNode root = ConfigText.read(new String(body, StandardCharsets.UTF_8), TREE_MAPPER);
            answer.put("tree", TREE_MAPPER.convertValue(root, new TypeReference<Map<String, Object>>() { }));
        }
        return answer;
    }

    private static List<Warning> warnings(PermissionSet permissions,
                                          Function<PermissionSet, List<RuleProblem>> schemaCheck) {
        if (schemaCheck == null) {
            return List.of();
        }
        try {
            return schemaCheck.apply(permissions).stream().map(Warning::of).toList();
        } catch (RuntimeException e) {
            // a schema that is reloading or a database with no permission dialect says nothing: no warning, never a refusal
            log.warn("validate_schema_check_skipped reason={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static Result refused(String error) {
        return new Result(false, List.of(error), 0, 0);
    }
}
