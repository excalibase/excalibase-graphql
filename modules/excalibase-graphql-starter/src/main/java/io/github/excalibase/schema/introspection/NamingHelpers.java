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
        return NamingUtils.typeNameOf(tableKey);
    }

    /** Derive the GraphQL field name from a table key. Compound keys always prefix. */
    static String fieldName(String tableKey) {
        return NamingUtils.fieldNameOf(tableKey);
    }
}
