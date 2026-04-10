package io.github.excalibase.rest.service;

import io.github.excalibase.schema.SchemaInfo;

import java.util.*;

public final class OpenApiGenerator {

    private OpenApiGenerator() {}

    public static Map<String, Object> generate(SchemaInfo schemaInfo, String defaultSchema, int port) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.3");
        spec.put("info", Map.of(
            "title", "Excalibase REST API",
            "version", "1.0.0",
            "description", "Auto-generated from database schema"
        ));
        spec.put("servers", List.of(Map.of("url", "/api/v1")));

        Map<String, Object> paths = new LinkedHashMap<>();
        Map<String, Object> schemas = new LinkedHashMap<>();

        for (String tableKey : schemaInfo.getTableNames()) {
            String schema = schemaInfo.getTableSchema(tableKey);
            if (schema != null && !schema.equals(defaultSchema)) continue;

            String raw = tableKey.contains(".") ? tableKey.substring(tableKey.indexOf('.') + 1) : tableKey;
            String typeName = capitalize(raw);
            boolean isView = schemaInfo.isView(tableKey);

            Map<String, Object> properties = new LinkedHashMap<>();
            for (String col : schemaInfo.getColumns(tableKey)) {
                String type = schemaInfo.getColumnType(tableKey, col);
                properties.put(col, mapColumnType(type));
            }
            schemas.put(typeName, Map.of("type", "object", "properties", properties));

            Map<String, Object> pathItem = new LinkedHashMap<>();
            pathItem.put("get", buildGetOp(raw, typeName));
            if (!isView) {
                pathItem.put("post", buildPostOp(raw, typeName));
                pathItem.put("patch", buildPatchOp(raw, typeName));
                pathItem.put("delete", buildDeleteOp(raw));
            }
            paths.put("/" + raw, pathItem);
        }

        spec.put("paths", paths);
        spec.put("components", Map.of("schemas", schemas));
        return spec;
    }

    private static Map<String, Object> buildGetOp(String table, String typeName) {
        return Map.of(
            "summary", "List " + table,
            "parameters", List.of(
                param("select", "Columns to return"),
                param("order", "Sort order (col.asc/desc)"),
                param("limit", "Max rows"),
                param("offset", "Skip rows")
            ),
            "responses", Map.of("200", Map.of(
                "description", "OK",
                "content", Map.of("application/json", Map.of(
                    "schema", Map.of("type", "object", "properties", Map.of(
                        "data", Map.of("type", "array", "items", ref(typeName))
                    ))
                ))
            ))
        );
    }

    private static Map<String, Object> buildPostOp(String table, String typeName) {
        return Map.of(
            "summary", "Create " + table,
            "requestBody", Map.of("content", Map.of("application/json", Map.of("schema", ref(typeName)))),
            "responses", Map.of("201", Map.of("description", "Created"))
        );
    }

    private static Map<String, Object> buildPatchOp(String table, String typeName) {
        return Map.of(
            "summary", "Update " + table,
            "requestBody", Map.of("content", Map.of("application/json", Map.of("schema", ref(typeName)))),
            "responses", Map.of("200", Map.of("description", "OK"))
        );
    }

    private static Map<String, Object> buildDeleteOp(String table) {
        return Map.of(
            "summary", "Delete " + table,
            "responses", Map.of("200", Map.of("description", "OK"))
        );
    }

    private static Map<String, Object> param(String name, String desc) {
        return Map.of("name", name, "in", "query", "required", false, "schema", Map.of("type", "string"), "description", desc);
    }

    private static Map<String, Object> ref(String typeName) {
        return Map.of("$ref", "#/components/schemas/" + typeName);
    }

    private static Map<String, Object> mapColumnType(String pgType) {
        if (pgType == null) return Map.of("type", "string");
        return switch (pgType.toLowerCase()) {
            case "integer", "int4", "serial", "smallint", "int2" -> Map.of("type", "integer");
            case "bigint", "int8", "bigserial" -> Map.of("type", "integer", "format", "int64");
            case "numeric", "decimal", "float8", "double precision" -> Map.of("type", "number");
            case "real", "float4" -> Map.of("type", "number", "format", "float");
            case "boolean", "bool" -> Map.of("type", "boolean");
            case "jsonb", "json" -> Map.of("type", "object");
            case "timestamp", "timestamptz" -> Map.of("type", "string", "format", "date-time");
            case "date" -> Map.of("type", "string", "format", "date");
            case "uuid" -> Map.of("type", "string", "format", "uuid");
            default -> Map.of("type", "string");
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        for (String part : s.split("_")) {
            if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
