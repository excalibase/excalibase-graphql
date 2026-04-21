package io.github.excalibase.schema.introspection;

import io.github.excalibase.schema.NamingUtils;

/**
 * Internal naming helpers shared by the introspection factories.
 * Compound table keys ("schema.table") are always schema-prefixed so
 * types across schemas never collide; simple keys use the default
 * PascalCase / lowerCamelCase rules from {@link NamingUtils}.
 */
final class NamingHelpers {

    private NamingHelpers() {}

    /** Derive the GraphQL type name from a table key. Compound keys always prefix. */
    static String typeName(String tableKey) {
        if (tableKey.contains(".")) {
            String schema = tableKey.substring(0, tableKey.indexOf('.'));
            String rawTable = tableKey.substring(tableKey.indexOf('.') + 1);
            return NamingUtils.schemaTypeName(schema, rawTable);
        }
        return NamingUtils.capitalize(tableKey);
    }

    /** Derive the GraphQL field name from a table key. Compound keys always prefix. */
    static String fieldName(String tableKey) {
        if (tableKey.contains(".")) {
            String schema = tableKey.substring(0, tableKey.indexOf('.'));
            String rawTable = tableKey.substring(tableKey.indexOf('.') + 1);
            return NamingUtils.schemaFieldName(schema, rawTable);
        }
        return NamingUtils.toLowerCamelCase(tableKey);
    }
}
