package io.github.excalibase;

import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.compiler.SqlCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the SQL compiler correctly handles multi-schema queries:
 * - Prefixed field names resolve to correct table+schema
 * - SQL uses correct schema-qualified table references
 * - Single schema backward compatibility preserved
 */
class MultiSchemaCompilerTest {

    private static final SqlDialect testDialect = new TestDialect();

    // === Single schema: backward compatible, no prefix ===

    @Test
    void singleSchema_fieldName_noPrefix() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("users", "id", "integer");
        info.addColumn("users", "name", "varchar");
        info.addPrimaryKey("users", "id");
        info.setTableSchema("users", "public");

        SqlCompiler compiler = new SqlCompiler(info, "public", 30, testDialect, new NoOpMutationCompiler());
        SqlCompiler.CompiledQuery result = compiler.compile("{ users { id name } }");

        assertNotNull(result);
        assertTrue(result.sql().contains("public.\"users\""));
    }

    // === Multi-schema: prefixed field names ===

    @Test
    void multiSchema_prefixedFieldName_resolvesToCorrectTable() {
        SchemaInfo info = buildMultiSchemaInfo();

        SqlCompiler compiler = new SqlCompiler(info, "public", 30, testDialect, new NoOpMutationCompiler());

        // "publicUsers" should resolve to the users table in public schema
        SqlCompiler.CompiledQuery result = compiler.compile("{ publicUsers { id name } }");
        assertNotNull(result);
        assertTrue(result.sql().contains("public.\"users\""),
                "Should query public.users — got: " + result.sql());
    }

    @Test
    void multiSchema_prefixedFieldName_differentSchema() {
        SchemaInfo info = buildMultiSchemaInfo();

        SqlCompiler compiler = new SqlCompiler(info, "public", 30, testDialect, new NoOpMutationCompiler());

        // "salesUsers" should resolve to the users table in sales schema
        SqlCompiler.CompiledQuery result = compiler.compile("{ salesUsers { id email } }");
        assertNotNull(result);
        assertTrue(result.sql().contains("sales.\"users\""),
                "Should query sales.users — got: " + result.sql());
    }

    @Test
    void multiSchema_connectionQuery_prefixed() {
        SchemaInfo info = buildMultiSchemaInfo();

        SqlCompiler compiler = new SqlCompiler(info, "public", 30, testDialect, new NoOpMutationCompiler());

        SqlCompiler.CompiledQuery result = compiler.compile(
                "{ publicUsersConnection(first: 5) { edges { node { id name } } } }");
        assertNotNull(result);
        assertTrue(result.sql().contains("public.\"users\""),
                "Connection should query public.users — got: " + result.sql());
    }

    @Test
    void multiSchema_aggregateQuery_prefixed() {
        SchemaInfo info = buildMultiSchemaInfo();

        SqlCompiler compiler = new SqlCompiler(info, "public", 30, testDialect, new NoOpMutationCompiler());

        SqlCompiler.CompiledQuery result = compiler.compile(
                "{ salesUsersAggregate { count } }");
        assertNotNull(result);
        assertTrue(result.sql().contains("sales.\"users\""),
                "Aggregate should query sales.users — got: " + result.sql());
    }

    @Test
    void multiSchema_crossSchemaFk() {
        SchemaInfo info = new SchemaInfo();
        // public.users
        info.addColumn("public.users", "id", "integer");
        info.addColumn("public.users", "name", "varchar");
        info.addPrimaryKey("public.users", "id");
        info.setTableSchema("public.users", "public");

        // sales.orders with FK to public.users
        info.addColumn("sales.orders", "id", "integer");
        info.addColumn("sales.orders", "user_id", "integer");
        info.addColumn("sales.orders", "total", "numeric");
        info.addPrimaryKey("sales.orders", "id");
        info.setTableSchema("sales.orders", "sales");
        info.addForeignKey("sales.orders", "user_id", "public.users", "id");

        SqlCompiler compiler = new SqlCompiler(info, "public", 30, testDialect, new NoOpMutationCompiler());

        // Query sales.orders with FK traversal to public.users (field name = column: user_id → salesUserId)
        SqlCompiler.CompiledQuery result = compiler.compile(
                "{ salesOrders { id total salesUserId { id name } } }");
        assertNotNull(result);
        String sql = result.sql();
        assertTrue(sql.contains("sales.\"orders\""), "Should query sales.orders — got: " + sql);
        assertTrue(sql.contains("public.\"users\""), "FK should resolve to public.users — got: " + sql);
    }

    // === Helpers ===

    /** Build SchemaInfo with same table name in two schemas */
    private SchemaInfo buildMultiSchemaInfo() {
        SchemaInfo info = new SchemaInfo();

        // public.users
        info.addColumn("public.users", "id", "integer");
        info.addColumn("public.users", "name", "varchar");
        info.addPrimaryKey("public.users", "id");
        info.setTableSchema("public.users", "public");

        // sales.users (different columns)
        info.addColumn("sales.users", "id", "integer");
        info.addColumn("sales.users", "email", "varchar");
        info.addPrimaryKey("sales.users", "id");
        info.setTableSchema("sales.users", "sales");

        return info;
    }

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

    private static class TestDialect implements SqlDialect {
        @Override public String buildObject(List<String> pairs) {
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
        @Override public String onConflict(List<String> cols, List<String> sets) { return ""; }
        @Override public boolean supportsReturning() { return true; }
        @Override public String cteName(String alias, String suffix) { return "\"" + alias + "_" + suffix + "\""; }
        @Override public String randAlias() { return "\"t" + System.nanoTime() % 10000 + "\""; }
        @Override public String distinctOn(List<String> cols, String alias) { return ""; }
    }
}
