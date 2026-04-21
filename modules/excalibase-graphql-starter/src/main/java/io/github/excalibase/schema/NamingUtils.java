package io.github.excalibase.schema;

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
        for (char ch : name.toCharArray()) {
            if (ch == '_') { nextUpper = true; }
            else if (first) { sb.append(Character.toLowerCase(ch)); first = false; }
            else if (nextUpper) { sb.append(Character.toUpperCase(ch)); nextUpper = false; }
            else sb.append(ch);
        }
        return sb.toString();
    }

    /** snake_case → PascalCase: "order_items" → "OrderItems" */
    public static String capitalize(String name) {
        if (name == null) return null;
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = true;
        for (char ch : name.toCharArray()) {
            if (ch == '_') { nextUpper = true; }
            else if (nextUpper) { sb.append(Character.toUpperCase(ch)); nextUpper = false; }
            else sb.append(ch);
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
