package io.github.excalibase.permissions;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.excalibase.security.Principal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.github.excalibase.permissions.JsonValues.fail;

/**
 * Reads the engine read endpoint's document (docs/features/permissions.md §8) into a
 * {@link PermissionSet}. It is strict on purpose: unknown keys, a missing filter or check, a bad
 * name, a duplicate entry or a function permission for an untracked function refuse the whole
 * document with {@link PermissionDocumentException}. Nothing is defaulted to "every row".
 */
public final class PermissionSetParser {

    private static final Set<String> DOCUMENT_KEYS =
            Set.of("projectId", "version", "tables", "functions", "functionPermissions");
    private static final Set<String> TABLE_KEYS = Set.of("table", "role", "select", "insert", "update", "delete");
    private static final Set<String> SELECT_KEYS = Set.of("filter", "columns", "limit", "allowAggregations");
    private static final Set<String> INSERT_KEYS = Set.of("check", "columns", "set");
    private static final Set<String> UPDATE_KEYS = Set.of("filter", "check", "columns", "set");
    private static final Set<String> DELETE_KEYS = Set.of("filter");
    private static final Set<String> FUNCTION_KEYS = Set.of("function", "exposedAs", "inferPermissions", "sessionArgument");
    private static final Set<String> FUNCTION_PERMISSION_KEYS = Set.of("function", "role");
    private static final String ALL_COLUMNS = "*";

    private PermissionSetParser() {
    }

    public static PermissionSet parse(JsonNode document) {
        JsonValues.object(document, "document", DOCUMENT_KEYS);
        String projectId = JsonValues.text(document, "projectId", "document");
        long version = version(document.get("version"));
        List<TablePermissions> tables = entries(document, "tables", PermissionSetParser::tablePermissions);
        List<TrackedFunction> functions = entries(document, "functions", PermissionSetParser::trackedFunction);
        List<FunctionPermission> grants =
                entries(document, "functionPermissions", PermissionSetParser::functionPermission);
        requireUnique(tables, entry -> entry.table() + " for role " + entry.role(), "tables");
        requireUnique(functions, TrackedFunction::function, "functions");
        requireUnique(grants, grant -> grant.function() + " for role " + grant.role(), "functionPermissions");
        requireTracked(functions, grants);
        return new PermissionSet(projectId, version, tables, functions, grants);
    }

    private static long version(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw fail("document.version", "must be a non-negative integer");
        }
        return node.longValue();
    }

    private static <T> List<T> entries(JsonNode document, String key, BiFunction<JsonNode, String, T> reader) {
        JsonNode array = JsonValues.array(document, key, "document");
        List<T> parsed = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            parsed.add(reader.apply(array.get(index), key + "[" + index + "]"));
        }
        return parsed;
    }

    private static <T> void requireUnique(List<T> entries, Function<T, String> identity,
                                          String key) {
        Set<String> seen = new HashSet<>();
        for (T entry : entries) {
            if (!seen.add(identity.apply(entry))) {
                throw fail(key, "duplicate entry " + identity.apply(entry));
            }
        }
    }

    private static void requireTracked(List<TrackedFunction> functions, List<FunctionPermission> grants) {
        Set<String> tracked = new HashSet<>();
        functions.forEach(function -> tracked.add(function.function()));
        for (FunctionPermission grant : grants) {
            if (!tracked.contains(grant.function())) {
                throw fail("functionPermissions", grant.function() + " is not a tracked function");
            }
        }
    }

    private static TablePermissions tablePermissions(JsonNode node, String path) {
        JsonValues.object(node, path, TABLE_KEYS);
        return new TablePermissions(
                JsonValues.matching(node, "table", path, JsonValues.QUALIFIED_NAME),
                role(node, path),
                operation(node, "select", path, PermissionSetParser::select),
                operation(node, "insert", path, PermissionSetParser::insert),
                operation(node, "update", path, PermissionSetParser::update),
                operation(node, "delete", path, PermissionSetParser::delete));
    }

    private static <T> Optional<T> operation(JsonNode entry, String key, String path,
                                             BiFunction<JsonNode, String, T> reader) {
        return entry.has(key) ? Optional.of(reader.apply(entry.get(key), path + "." + key)) : Optional.empty();
    }

    private static SelectPermission select(JsonNode node, String path) {
        JsonValues.object(node, path, SELECT_KEYS);
        boolean allowAggregations = node.has("allowAggregations") && JsonValues.bool(node, "allowAggregations", path);
        return new SelectPermission(expression(node, "filter", path), columns(node, path),
                limit(node.get("limit"), path + ".limit"), allowAggregations);
    }

    private static InsertPermission insert(JsonNode node, String path) {
        JsonValues.object(node, path, INSERT_KEYS);
        return new InsertPermission(expression(node, "check", path), columns(node, path), presets(node, path));
    }

    private static UpdatePermission update(JsonNode node, String path) {
        JsonValues.object(node, path, UPDATE_KEYS);
        return new UpdatePermission(expression(node, "filter", path), expression(node, "check", path),
                columns(node, path), presets(node, path));
    }

    private static DeletePermission delete(JsonNode node, String path) {
        JsonValues.object(node, path, DELETE_KEYS);
        return new DeletePermission(expression(node, "filter", path));
    }

    private static BoolExp expression(JsonNode node, String key, String path) {
        return BoolExpParser.parse(node.get(key), path + "." + key);
    }

    private static Integer limit(JsonNode node, String path) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() <= 0) {
            throw fail(path, "must be a positive integer or null");
        }
        return node.intValue();
    }

    private static ColumnSet columns(JsonNode node, String path) {
        JsonNode columns = node.get("columns");
        String where = path + ".columns";
        if (columns != null && columns.isTextual() && ALL_COLUMNS.equals(columns.textValue())) {
            return ColumnSet.ALL;
        }
        if (columns == null || !columns.isArray()) {
            throw fail(where, "must be \"*\" or an array of column names");
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode column : columns) {
            if (!column.isTextual() || !JsonValues.FIELD.matcher(column.textValue()).matches()) {
                throw fail(where, "holds an invalid column name");
            }
            if (!names.add(column.textValue())) {
                throw fail(where, "lists " + column.textValue() + " twice");
            }
        }
        return ColumnSet.of(List.copyOf(names));
    }

    private static Map<String, PresetValue> presets(JsonNode node, String path) {
        JsonNode set = node.get("set");
        String where = path + ".set";
        if (set == null) {
            return Map.of();
        }
        if (!set.isObject()) {
            throw fail(where, "must be an object");
        }
        Map<String, PresetValue> presets = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : set.properties()) {
            if (!JsonValues.FIELD.matcher(entry.getKey()).matches()) {
                throw fail(where, "holds an invalid column name");
            }
            presets.put(entry.getKey(), preset(entry.getValue(), where + "." + entry.getKey()));
        }
        return presets;
    }

    private static PresetValue preset(JsonNode value, String path) {
        if (value.isTextual() && SessionVariable.looksLikeVariable(value.textValue())) {
            if (!SessionVariable.isValidName(value.textValue())) {
                throw fail(path, "names an invalid session variable");
            }
            return new SessionVariable(value.textValue());
        }
        return new Literal(JsonValues.plain(value));
    }

    private static String role(JsonNode node, String path) {
        String role = JsonValues.text(node, "role", path);
        if (!Principal.isValidRoleName(role) || Principal.SERVICE.equals(role)) {
            throw fail(path + ".role", "is not a permission-governed role");
        }
        return role;
    }

    private static TrackedFunction trackedFunction(JsonNode node, String path) {
        JsonValues.object(node, path, FUNCTION_KEYS);
        return new TrackedFunction(
                JsonValues.matching(node, "function", path, JsonValues.QUALIFIED_NAME),
                exposedAs(JsonValues.text(node, "exposedAs", path), path),
                JsonValues.bool(node, "inferPermissions", path),
                sessionArgument(node.get("sessionArgument"), path + ".sessionArgument"));
    }

    private static TrackedFunction.ExposedAs exposedAs(String value, String path) {
        return switch (value) {
            case "QUERY" -> TrackedFunction.ExposedAs.QUERY;
            case "MUTATION" -> TrackedFunction.ExposedAs.MUTATION;
            default -> throw fail(path + ".exposedAs", "must be QUERY or MUTATION");
        };
    }

    private static String sessionArgument(JsonNode node, String path) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || !JsonValues.FIELD.matcher(node.textValue()).matches()) {
            throw fail(path, "must be an argument name or null");
        }
        return node.textValue();
    }

    private static FunctionPermission functionPermission(JsonNode node, String path) {
        JsonValues.object(node, path, FUNCTION_PERMISSION_KEYS);
        return new FunctionPermission(
                JsonValues.matching(node, "function", path, JsonValues.QUALIFIED_NAME), role(node, path));
    }
}
