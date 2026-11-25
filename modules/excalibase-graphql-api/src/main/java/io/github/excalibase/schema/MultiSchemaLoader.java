package io.github.excalibase.schema;

import io.github.excalibase.spi.SchemaLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflects the loaded schemas into one {@link SchemaInfo}. A reserved schema is never merged, and a function
 * returning rows of a reserved table is never kept, even if a loader hands them over: the engine's own
 * schemas are not served, whatever reached this point.
 */
final class MultiSchemaLoader {

    private final ReservedSchemas reservedSchemas;

    MultiSchemaLoader(ReservedSchemas reservedSchemas) {
        this.reservedSchemas = reservedSchemas;
    }

    void load(SchemaInfo schemaInfo, List<String> schemaList, SchemaLoader loader, JdbcTemplate jdbc) {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbc, schemaList, perSchema);

        SchemaMerger merger = new SchemaMerger();
        for (var schemaEntry : perSchema.entrySet()) {
            String schema = schemaEntry.getKey();
            SchemaInfo temp = schemaEntry.getValue();
            if (reservedSchemas.isReserved(schema)) {
                continue;
            }
            merger.merge(schemaInfo, schema, temp);
            mergeEnumsProcsAndComposites(schemaInfo, schema, temp);
            // Extensions are global to the database, so every per-schema temp
            // carries the same set. Copying once onto the target is enough —
            // duplicates are harmless since addExtension uses a map put.
            for (var ext : temp.getExtensions().entrySet()) {
                schemaInfo.addExtension(ext.getKey(), ext.getValue());
            }
        }
        mergeForeignKeys(schemaInfo, perSchema, schemaList);
    }

    private void mergeEnumsProcsAndComposites(SchemaInfo target, String schema, SchemaInfo source) {
        for (var entry : source.getEnumTypes().entrySet()) {
            for (String label : entry.getValue()) {
                target.addEnumValue(schema + "." + entry.getKey(), label);
            }
        }
        for (var entry : source.getFunctions().entrySet()) {
            if (!reservedSchemas.isReservedObject(entry.getValue().returnTable())) {
                target.addFunction(schema + "." + entry.getKey(), entry.getValue());
            }
        }
        for (var entry : source.getCompositeTypes().entrySet()) {
            for (var field : entry.getValue()) {
                target.addCompositeTypeField(schema + "." + entry.getKey(), field.name(), field.type());
            }
        }
    }

    private void mergeForeignKeys(SchemaInfo target, Map<String, SchemaInfo> perSchema, List<String> schemaList) {
        for (var schemaEntry : perSchema.entrySet()) {
            String schema = schemaEntry.getKey();
            SchemaInfo temp = schemaEntry.getValue();
            for (var entry : temp.getAllForwardFks().entrySet()) {
                String fkKey = entry.getKey();
                String fromTable = fkKey.substring(0, fkKey.lastIndexOf('.'));
                SchemaInfo.FkInfo fk = entry.getValue();
                String compoundFrom = schema + "." + fromTable;
                String compoundTo = findCompoundKey(target, fk, schemaList, schema);
                if (compoundTo != null && target.hasTable(compoundFrom)) {
                    if (fk.isComposite()) {
                        target.addCompositeForeignKey(compoundFrom, fk.fkColumns(), compoundTo, fk.refColumns());
                    } else {
                        target.addForeignKey(compoundFrom, fk.fkColumn(), compoundTo, fk.refColumn());
                    }
                }
            }
        }
    }

    /**
     * The merged name of the table {@code fk} points at. A key that names its schema binds there and nowhere
     * else, even when another loaded schema has a table of the same name; one that does not is looked for
     * in its own schema first.
     */
    private String findCompoundKey(SchemaInfo schemaInfo, SchemaInfo.FkInfo fk, List<String> allSchemas,
                                   String preferredSchema) {
        String rawTable = fk.refTable();
        if (fk.refSchema() != null) {
            String named = fk.refSchema() + "." + rawTable;
            return schemaInfo.hasTable(named) ? named : null;
        }
        if (preferredSchema != null) {
            String candidate = preferredSchema + "." + rawTable;
            if (schemaInfo.hasTable(candidate)) return candidate;
        }
        for (String schema : allSchemas) {
            if (schema.equals(preferredSchema)) continue;
            String candidate = schema + "." + rawTable;
            if (schemaInfo.hasTable(candidate)) return candidate;
        }
        return null;
    }
}
