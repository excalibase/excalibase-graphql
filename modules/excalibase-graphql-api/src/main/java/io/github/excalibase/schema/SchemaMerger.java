package io.github.excalibase.schema;

/**
 * Merges a per-schema {@link SchemaInfo} (source) into a combined target.
 * Each responsibility lives in its own small method so the outer
 * {@link #merge} stays a readable orchestrator.
 * <p>
 * Package-private — used only by {@link GraphqlSchemaManager} to assemble
 * a multi-schema {@code SchemaInfo}.
 */
final class SchemaMerger {

    void merge(SchemaInfo target, String schema, SchemaInfo source) {
        for (String table : source.getTableNames()) {
            String key = schema + "." + table;
            mergeColumns(target, source, schema, table, key);
            target.setTableSchema(key, schema);
            mergePrimaryKeys(target, source, table, key);
            mergeViews(target, source, table, key);
            mergeComputedFields(target, source, table, key);
        }
    }

    private void mergeColumns(SchemaInfo target, SchemaInfo source, String schema, String table, String key) {
        for (String col : source.getColumns(table)) {
            target.addColumn(key, col, source.getColumnType(table, col));
            mergeEnumColumns(target, source, schema, table, col, key);
        }
    }

    private void mergeEnumColumns(SchemaInfo target, SchemaInfo source, String schema,
                                  String table, String col, String key) {
        String enumType = source.getEnumType(table, col);
        if (enumType != null) {
            target.addColumnEnumType(key, col, schema + "." + enumType);
        }
    }

    private void mergePrimaryKeys(SchemaInfo target, SchemaInfo source, String table, String key) {
        if (!source.hasPrimaryKey(table)) return;
        for (String pk : source.getPrimaryKeys(table)) {
            target.addPrimaryKey(key, pk);
        }
    }

    private void mergeViews(SchemaInfo target, SchemaInfo source, String table, String key) {
        if (source.isView(table)) {
            target.addView(key);
        }
    }

    private void mergeComputedFields(SchemaInfo target, SchemaInfo source, String table, String key) {
        var computed = source.getComputedFields(table);
        if (computed == null) return;
        for (var cf : computed) {
            target.addComputedField(key, cf.functionName(), cf.returnType());
        }
    }
}
