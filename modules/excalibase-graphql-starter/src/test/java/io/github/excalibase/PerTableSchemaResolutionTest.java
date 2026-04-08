package io.github.excalibase;

import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.compiler.SqlCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the SQL compiler uses per-table schema metadata
 * (from SchemaInfo.getTableSchema) instead of the global dbSchema
 * when generating qualified table references in SQL.
 */
class PerTableSchemaResolutionTest {

    /** Minimal SqlDialect that makes schema resolution visible in output. */
    private static final SqlDialect testDialect = new TestDialect();

    private SchemaInfo schemaInfo;

    @BeforeEach
    void setUp() {
        schemaInfo = new SchemaInfo();
    }

    // === Single schema — uses global default ===

    @Test
    void compile_singleSchema_usesGlobalDbSchema() {
        schemaInfo.addColumn("users", "id", "integer");
        schemaInfo.addColumn("users", "name", "varchar");
        schemaInfo.addPrimaryKey("users", "id");
        // No setTableSchema — should fall back to global "dvdrental"

        SqlCompiler compiler = new SqlCompiler(schemaInfo, "dvdrental", 30,
                testDialect, new NoOpMutationCompiler());

        SqlCompiler.CompiledQuery result = compiler.compile("{ users { id name } }");

        assertNotNull(result);
        assertTrue(result.sql().contains("dvdrental.\"users\""),
                "SQL should use global schema 'dvdrental' — got: " + result.sql());
    }

    // === Per-table schema — overrides global default ===

    @Test
    void compile_perTableSchema_usesTableSpecificSchema() {
        schemaInfo.addColumn("users", "id", "integer");
        schemaInfo.addColumn("users", "name", "varchar");
        schemaInfo.addPrimaryKey("users", "id");
        schemaInfo.setTableSchema("users", "sales");

        SqlCompiler compiler = new SqlCompiler(schemaInfo, "dvdrental", 30,
                testDialect, new NoOpMutationCompiler());

        SqlCompiler.CompiledQuery result = compiler.compile("{ users { id name } }");

        assertNotNull(result);
        assertTrue(result.sql().contains("sales.\"users\""),
                "SQL should use per-table schema 'sales' — got: " + result.sql());
        assertFalse(result.sql().contains("dvdrental.\"users\""),
                "SQL should NOT use global schema 'dvdrental' — got: " + result.sql());
    }

    // === Multiple tables from different schemas ===

    @Test
    void compile_multipleSchemas_eachTableUsesItsOwnSchema() {
        schemaInfo.addColumn("users", "id", "integer");
        schemaInfo.addColumn("users", "name", "varchar");
        schemaInfo.addPrimaryKey("users", "id");
        schemaInfo.setTableSchema("users", "public");

        schemaInfo.addColumn("orders", "id", "integer");
        schemaInfo.addColumn("orders", "total", "numeric");
        schemaInfo.addPrimaryKey("orders", "id");
        schemaInfo.setTableSchema("orders", "sales");

        SqlCompiler compiler = new SqlCompiler(schemaInfo, "default_schema", 30,
                testDialect, new NoOpMutationCompiler());

        SqlCompiler.CompiledQuery usersResult = compiler.compile("{ users { id name } }");
        SqlCompiler.CompiledQuery ordersResult = compiler.compile("{ orders { id total } }");

        assertTrue(usersResult.sql().contains("public.\"users\""),
                "users should use 'public' schema — got: " + usersResult.sql());
        assertTrue(ordersResult.sql().contains("sales.\"orders\""),
                "orders should use 'sales' schema — got: " + ordersResult.sql());
    }

    // === FK traversal uses correct schema for referenced table ===

    @Test
    void compile_fkTraversal_usesReferencedTableSchema() {
        // users in "public" schema
        schemaInfo.addColumn("users", "id", "integer");
        schemaInfo.addColumn("users", "name", "varchar");
        schemaInfo.addPrimaryKey("users", "id");
        schemaInfo.setTableSchema("users", "public");

        // orders in "sales" schema, FK to public.users
        schemaInfo.addColumn("orders", "id", "integer");
        schemaInfo.addColumn("orders", "user_id", "integer");
        schemaInfo.addColumn("orders", "total", "numeric");
        schemaInfo.addPrimaryKey("orders", "id");
        schemaInfo.setTableSchema("orders", "sales");
        schemaInfo.addForeignKey("orders", "user_id", "users", "id");

        SqlCompiler compiler = new SqlCompiler(schemaInfo, "default_schema", 30,
                testDialect, new NoOpMutationCompiler());

        // Query orders with FK traversal to users (field name = column name: user_id → userId)
        SqlCompiler.CompiledQuery result = compiler.compile("{ orders { id total userId { id name } } }");

        assertNotNull(result);
        String sql = result.sql();
        assertTrue(sql.contains("sales.\"orders\""),
                "orders should use 'sales' schema — got: " + sql);
        assertTrue(sql.contains("public.\"users\""),
                "FK to users should use 'public' schema — got: " + sql);
    }

    // === Connection query uses per-table schema ===

    @Test
    void compile_connectionQuery_usesPerTableSchema() {
        schemaInfo.addColumn("products", "id", "integer");
        schemaInfo.addColumn("products", "name", "varchar");
        schemaInfo.addPrimaryKey("products", "id");
        schemaInfo.setTableSchema("products", "inventory");

        SqlCompiler compiler = new SqlCompiler(schemaInfo, "default_schema", 30,
                testDialect, new NoOpMutationCompiler());

        SqlCompiler.CompiledQuery result = compiler.compile(
                "{ productsConnection(first: 5) { edges { node { id name } } } }");

        assertNotNull(result);
        assertTrue(result.sql().contains("inventory.\"products\""),
                "Connection query should use 'inventory' schema — got: " + result.sql());
    }

    // === Aggregate query uses per-table schema ===

    @Test
    void compile_aggregateQuery_usesPerTableSchema() {
        schemaInfo.addColumn("products", "id", "integer");
        schemaInfo.addColumn("products", "price", "numeric");
        schemaInfo.addPrimaryKey("products", "id");
        schemaInfo.setTableSchema("products", "inventory");

        SqlCompiler compiler = new SqlCompiler(schemaInfo, "default_schema", 30,
                testDialect, new NoOpMutationCompiler());

        SqlCompiler.CompiledQuery result = compiler.compile(
                "{ productsAggregate { count } }");

        assertNotNull(result);
        assertTrue(result.sql().contains("inventory.\"products\""),
                "Aggregate query should use 'inventory' schema — got: " + result.sql());
    }

    // ========== Test helpers ==========

    /** No-op mutation compiler — mutations not tested here. */
    private static class NoOpMutationCompiler implements MutationCompiler {
        @Override
        public SqlCompiler.CompiledQuery compileMutation(
                graphql.language.Field field, String fieldName,
                java.util.Map<String, Object> params,
                java.util.Map<String, Object> variables,
                MutationBuilder shared) {
            return null;
        }
    }

    /**
     * Minimal test dialect that produces readable SQL with visible schema.table patterns.
     * Uses PostgreSQL-like quoting: schema."table"
     */
    private static class TestDialect implements SqlDialect {
        @Override public String buildObject(java.util.List<String> pairs) {
            return "jsonb_build_object(" + String.join(", ", pairs) + ")";
        }
        @Override public String aggregateArray(String expr) { return "jsonb_agg(" + expr + ")"; }
        @Override public String coalesceArray(String expr) { return "coalesce(" + expr + ", '[]'::jsonb)"; }
        @Override public String quoteIdentifier(String id) { return "\"" + id + "\""; }
        @Override public String qualifiedTable(String schema, String table) {
            return schema + ".\"" + table + "\"";
        }
        @Override public String encodeCursor(String expr) { return "encode(" + expr + ")"; }
        @Override public String ilike(String col, String param) { return col + " ILIKE " + param; }
        @Override public String orderByNulls(String col, String dir, String nulls) { return col + " " + dir; }
        @Override public String suffixCast(String type) { return ""; }
        @Override public String onConflict(java.util.List<String> cols, java.util.List<String> sets) { return ""; }
        @Override public boolean supportsReturning() { return true; }
        @Override public String cteName(String alias, String suffix) { return "\"" + alias + "_" + suffix + "\""; }
        @Override public String randAlias() { return "\"t" + System.nanoTime() % 10000 + "\""; }
        @Override public String distinctOn(java.util.List<String> cols, String alias) { return ""; }
    }
}
