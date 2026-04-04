package io.github.excalibase;

/**
 * Shared naming conversion utilities.
 * Centralizes snake_case ↔ camelCase ↔ PascalCase conversions
 * used across SchemaInfo, IntrospectionHandler, QueryBuilder, MutationBuilder.
 */
public final class NamingUtils {

    private NamingUtils() {}

    /** snake_case → lowerCamelCase: "order_items" → "orderItems" */
    public static String toLowerCamelCase(String name) {
        if (name == null) return null;
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        boolean first = true;
        for (char c : name.toCharArray()) {
            if (c == '_') { nextUpper = true; }
            else if (first) { sb.append(Character.toLowerCase(c)); first = false; }
            else if (nextUpper) { sb.append(Character.toUpperCase(c)); nextUpper = false; }
            else sb.append(c);
        }
        return sb.toString();
    }

    /** snake_case → PascalCase: "order_items" → "OrderItems" */
    public static String capitalize(String name) {
        if (name == null) return null;
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = true;
        for (char c : name.toCharArray()) {
            if (c == '_') { nextUpper = true; }
            else if (nextUpper) { sb.append(Character.toUpperCase(c)); nextUpper = false; }
            else sb.append(c);
        }
        return sb.toString();
    }

    /** camelCase/PascalCase → snake_case: "orderItems" → "order_items" */
    public static String camelToSnakeCase(String name) {
        if (name == null) return null;
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    /** Schema-prefixed PascalCase type name: ("public", "order_items") → "PublicOrderItems" */
    public static String schemaTypeName(String schema, String table) {
        return capitalize(schema) + capitalize(table);
    }

    /** Schema-prefixed lowerCamelCase field name: ("public", "order_items") → "publicOrderItems" */
    public static String schemaFieldName(String schema, String table) {
        return toLowerCamelCase(schema) + capitalize(table);
    }

}
