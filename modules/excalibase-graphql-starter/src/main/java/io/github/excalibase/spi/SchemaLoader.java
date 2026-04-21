package io.github.excalibase.spi;

import io.github.excalibase.schema.SchemaInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.List;
import java.util.Map;

/**
 * Interface for loading database schema metadata.
 * Implementations handle database-specific queries for columns, FKs, views, etc.
 */
public interface SchemaLoader {

    void loadColumns(JdbcTemplate jdbc, String schema, SchemaInfo info);

    /**
     * Bulk load all metadata for multiple schemas.
     * Default falls back to per-schema calls (8 × N queries).
     * Override in implementations for optimized single-query loading.
     */
    default void loadAll(JdbcTemplate jdbc, List<String> schemas, Map<String, SchemaInfo> perSchema) {
        if (schemas.isEmpty()) return;
        for (String schema : schemas) {
            SchemaInfo info = perSchema.computeIfAbsent(schema, k -> new SchemaInfo());
            loadColumns(jdbc, schema, info);
            loadPrimaryKeys(jdbc, schema, info);
            loadForeignKeys(jdbc, schema, info);
            loadViews(jdbc, schema, info);
            loadEnums(jdbc, schema, info);
            loadCompositeTypes(jdbc, schema, info);
            loadComputedFields(jdbc, schema, info);
            loadStoredProcedures(jdbc, schema, info);
        }
    }

    /** Load primary keys. Default uses standard information_schema (works for PG + MySQL). */
    default void loadPrimaryKeys(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT tc.table_name, kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
                AND tc.table_name = kcu.table_name
            WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = ?
            ORDER BY tc.table_name, kcu.ordinal_position
            """, (RowCallbackHandler) rs -> info.addPrimaryKey(rs.getString("table_name"), rs.getString("column_name")), schema);
    }

    void loadForeignKeys(JdbcTemplate jdbc, String schema, SchemaInfo info);

    void loadViews(JdbcTemplate jdbc, String schema, SchemaInfo info);

    void loadStoredProcedures(JdbcTemplate jdbc, String schema, SchemaInfo info);

    void loadEnums(JdbcTemplate jdbc, String schema, SchemaInfo info);

    void loadCompositeTypes(JdbcTemplate jdbc, String schema, SchemaInfo info);

    void loadComputedFields(JdbcTemplate jdbc, String schema, SchemaInfo info);
}
