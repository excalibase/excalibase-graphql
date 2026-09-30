package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.schema.SchemaInfo;

/** A reflected table: its key in {@link SchemaInfo} and its quoted, schema-qualified SQL name. */
record TableRef(String key, String sql) {

    static TableRef resolve(SchemaInfo schema, String tableKey) {
        if (tableKey == null || !schema.hasTable(tableKey)) {
            throw new PermissionEvaluationException(PermissionEvaluationException.UNKNOWN_TABLE,
                    "Unknown table " + tableKey);
        }
        String schemaName = schema.getTableSchema(tableKey);
        if (schemaName == null) {
            return new TableRef(tableKey, SqlNames.quote(tableKey));
        }
        String name = tableKey.startsWith(schemaName + ".") ? tableKey.substring(schemaName.length() + 1) : tableKey;
        return new TableRef(tableKey, SqlNames.quote(schemaName) + "." + SqlNames.quote(name));
    }
}
