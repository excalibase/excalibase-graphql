package io.github.excalibase;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test: when app.schemas is used (even single schema), types are ALWAYS prefixed.
 * This prevents client code from breaking when a second schema is added later.
 *
 * Rule:
 * - app.schemas (even 1) → always prefix
 */
class AlwaysPrefixNamingTest {

    private static final SqlDialect testDialect = new TestDialect();

    // === Single schema via compound key → ALWAYS prefix ===

    @Test
    void singleSchema_compoundKey_queryFieldIsPrefixed() {
        // Simulates app.schemas=public (1 schema, but using compound keys)
        SchemaInfo info = new SchemaInfo();
        info.addColumn("public.users", "id", "integer");
        info.addColumn("public.users", "name", "varchar");
        info.addPrimaryKey("public.users", "id");
        info.setTableSchema("public.users", "public");

        SqlCompiler compiler = new SqlCompiler(info, "public", 30, testDialect, new NoOpMutationCompiler());

        // "publicUsers" must resolve — because compound key means always-prefix
        SqlCompiler.CompiledQuery result = compiler.compile("{ publicUsers { id name } }");
        assertNotNull(result, "publicUsers should resolve for compound key public.users");
        assertTrue(result.sql().contains("public.\"users\""));
    }

    @Test
    void singleSchema_compoundKey_connectionIsPrefixed() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("public.users", "id", "integer");
        info.addColumn("public.users", "name", "varchar");
        info.addPrimaryKey("public.users", "id");
        info.setTableSchema("public.users", "public");

        SqlCompiler compiler = new SqlCompiler(info, "public", 30, testDialect, new NoOpMutationCompiler());

        SqlCompiler.CompiledQuery result = compiler.compile(
                "{ publicUsersConnection(first: 5) { edges { node { id } } } }");
        assertNotNull(result);
        assertTrue(result.sql().contains("public.\"users\""));
    }

    @Test
    void singleSchema_compoundKey_aggregateIsPrefixed() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("public.users", "id", "integer");
        info.addPrimaryKey("public.users", "id");
        info.setTableSchema("public.users", "public");

        SqlCompiler compiler = new SqlCompiler(info, "public", 30, testDialect, new NoOpMutationCompiler());

        SqlCompiler.CompiledQuery result = compiler.compile("{ publicUsersAggregate { count } }");
        assertNotNull(result);
        assertTrue(result.sql().contains("public.\"users\""));
    }

    @Test
    void singleSchema_compoundKey_unprefixedFieldName_doesNotResolve() {
        // When compound keys are used, bare "users" must NOT resolve
        SchemaInfo info = new SchemaInfo();
        info.addColumn("public.users", "id", "integer");
        info.addPrimaryKey("public.users", "id");
        info.setTableSchema("public.users", "public");

        SqlCompiler compiler = new SqlCompiler(info, "public", 30, testDialect, new NoOpMutationCompiler());

        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile("{ users { id } }"),
                "Bare 'users' should not resolve when compound keys are used");
    }

    // === Helpers ===

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
