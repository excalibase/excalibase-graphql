package io.github.excalibase.rest.service;

import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import io.github.excalibase.security.RlsOp;

import java.util.*;

public final class OpenApiGenerator {

    private static final String K_TYPE = "type";
    private static final String K_DESCRIPTION = "description";
    private static final String K_SUMMARY = "summary";
    private static final String K_RESPONSES = "responses";
    private static final String K_SCHEMA = "schema";
    private static final String K_CONTENT = "content";
    private static final String K_FORMAT = "format";
    private static final String T_STRING = "string";
    private static final String T_OBJECT = "object";
    private static final String T_INTEGER = "integer";
    private static final String MIME_JSON = "application/json";

    private OpenApiGenerator() {}

    public static Map<String, Object> generate(SchemaInfo schemaInfo, String defaultSchema) {
        return generate(schemaInfo, TableAccess.UNRESTRICTED, defaultSchema);
    }

    /**
     * The spec one caller is served: a path per table it may reach, with only the methods it holds. A
     * table it may insert into but not select has only POST, typed by the columns it may set.
     */
    public static Map<String, Object> generate(SchemaInfo schemaInfo, TableAccess access, String defaultSchema) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.3");
        spec.put("info", Map.of(
            "title", "Excalibase REST API",
            "version", "1.0.0",
            K_DESCRIPTION, "Auto-generated from database schema"
        ));
        // Routes are project-scoped: /{projectId}/api/v1/{table}. Expose the project
        // as an OpenAPI server variable so generated clients target the real path.
        spec.put("servers", List.of(Map.of(
            "url", "/{projectId}/api/v1",
            "variables", Map.of("projectId", Map.of(
                "default", "your-project-id",
                K_DESCRIPTION, "Project identifier — the single path segment your token is scoped to"
            ))
        )));

        Map<String, Object> paths = new LinkedHashMap<>();
        Map<String, Object> schemas = new LinkedHashMap<>();

        for (String tableKey : schemaInfo.getMutableTableNames()) {
            String schema = schemaInfo.getTableSchema(tableKey);
            if (schema != null && !schema.equals(defaultSchema)) continue;

            String raw = tableKey.contains(".") ? tableKey.substring(tableKey.indexOf('.') + 1) : tableKey;
            String typeName = capitalize(raw);
            boolean readable = schemaInfo.hasTable(tableKey);

            Map<String, Object> properties = new LinkedHashMap<>();
            Set<String> shown = readable ? schemaInfo.getColumns(tableKey)
                    : access.settableColumns(tableKey, RlsOp.INSERT, schemaInfo);
            for (String col : shown) {
                String type = schemaInfo.getColumnType(tableKey, col);
                properties.put(col, mapColumnType(type));
            }
            schemas.put(typeName, Map.of(K_TYPE, T_OBJECT, "properties", properties));

            paths.put("/" + raw, pathItem(schemaInfo, access, tableKey, raw, typeName));
        }

        spec.put("paths", paths);
        spec.put("components", Map.of("schemas", schemas));
        return spec;
    }

    /** The methods the caller holds on one table; a write-only table is never read, updated or deleted. */
    private static Map<String, Object> pathItem(SchemaInfo schemaInfo, TableAccess access, String tableKey,
                                                String raw, String typeName) {
        boolean readable = schemaInfo.hasTable(tableKey);
        boolean writable = !schemaInfo.isView(tableKey);
        Map<String, Object> pathItem = new LinkedHashMap<>();
        if (readable && access.permits(tableKey, RlsOp.SELECT)) {
            pathItem.put("get", buildGetOp(raw, typeName));
        }
        if (writable && access.permits(tableKey, RlsOp.INSERT)) {
            pathItem.put("post", buildPostOp(raw, typeName));
        }
        if (writable && readable && access.permits(tableKey, RlsOp.UPDATE)) {
            pathItem.put("patch", buildPatchOp(raw, typeName));
        }
        if (writable && readable && access.permits(tableKey, RlsOp.DELETE)) {
            pathItem.put("delete", buildDeleteOp(raw));
        }
        return pathItem;
    }

    private static Map<String, Object> buildGetOp(String table, String typeName) {
        return Map.of(
            K_SUMMARY, "List " + table,
            "parameters", List.of(
                param("select", "Columns to return"),
                param("order", "Sort order (col.asc/desc)"),
                param("limit", "Max rows"),
                param("offset", "Skip rows")
            ),
            K_RESPONSES, Map.of("200", Map.of(
                K_DESCRIPTION, "OK",
                K_CONTENT, Map.of(MIME_JSON, Map.of(
                    K_SCHEMA, Map.of(K_TYPE, T_OBJECT, "properties", Map.of(
                        "data", Map.of(K_TYPE, "array", "items", ref(typeName))
                    ))
                ))
            ))
        );
    }

    private static Map<String, Object> buildPostOp(String table, String typeName) {
        return Map.of(
            K_SUMMARY, "Create " + table,
            "requestBody", Map.of(K_CONTENT, Map.of(MIME_JSON, Map.of(K_SCHEMA, ref(typeName)))),
            K_RESPONSES, Map.of("201", Map.of(K_DESCRIPTION, "Created"))
        );
    }

    private static Map<String, Object> buildPatchOp(String table, String typeName) {
        return Map.of(
            K_SUMMARY, "Update " + table,
            "requestBody", Map.of(K_CONTENT, Map.of(MIME_JSON, Map.of(K_SCHEMA, ref(typeName)))),
            K_RESPONSES, Map.of("200", Map.of(K_DESCRIPTION, "OK"))
        );
    }

    private static Map<String, Object> buildDeleteOp(String table) {
        return Map.of(
            K_SUMMARY, "Delete " + table,
            K_RESPONSES, Map.of("200", Map.of(K_DESCRIPTION, "OK"))
        );
    }

    private static Map<String, Object> param(String name, String desc) {
        return Map.of("name", name, "in", "query", "required", false, K_SCHEMA, Map.of(K_TYPE, T_STRING), K_DESCRIPTION, desc);
    }

    private static Map<String, Object> ref(String typeName) {
        return Map.of("$ref", "#/components/schemas/" + typeName);
    }

    private static Map<String, Object> mapColumnType(String pgType) {
        if (pgType == null) return Map.of(K_TYPE, T_STRING);
        return switch (pgType.toLowerCase()) {
            case T_INTEGER, "int4", "serial", "smallint", "int2" -> Map.of(K_TYPE, T_INTEGER);
            case "bigint", "int8", "bigserial" -> Map.of(K_TYPE, T_INTEGER, K_FORMAT, "int64");
            case "numeric", "decimal", "float8", "double precision" -> Map.of(K_TYPE, "number");
            case "real", "float4" -> Map.of(K_TYPE, "number", K_FORMAT, "float");
            case "boolean", "bool" -> Map.of(K_TYPE, "boolean");
            case "jsonb", "json" -> Map.of(K_TYPE, T_OBJECT);
            case "timestamp", "timestamptz" -> Map.of(K_TYPE, T_STRING, K_FORMAT, "date-time");
            case "date" -> Map.of(K_TYPE, T_STRING, K_FORMAT, "date");
            case "uuid" -> Map.of(K_TYPE, T_STRING, K_FORMAT, "uuid");
            default -> Map.of(K_TYPE, T_STRING);
        };
    }

    private static String capitalize(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder sb = new StringBuilder();
        for (String part : input.split("_")) {
            if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
