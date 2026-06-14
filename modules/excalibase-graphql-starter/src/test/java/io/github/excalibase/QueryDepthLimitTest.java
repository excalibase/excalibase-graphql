package io.github.excalibase;

import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.MutationCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the GraphQL query-depth guard (app.max-query-depth → SqlCompiler maxDepth).
 *
 * <p>A self-referential FK (employee.manager_id → employee.id) lets a query nest
 * arbitrarily deep via the forward-FK field, so we can build queries just under and
 * just over the configured limit.
 */
class QueryDepthLimitTest {

    private static final SqlDialect testDialect = new TestDialect();

    private SchemaInfo selfReferentialSchema() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("employee", "id", "integer");
        info.addColumn("employee", "name", "varchar");
        info.addColumn("employee", "manager_id", "integer");
        info.addPrimaryKey("employee", "id");
        info.setTableSchema("employee", "public");
        // forward FK field: managerId → employee
        info.addForeignKey("employee", "manager_id", "employee", "id");
        return info;
    }

    /** Build "{ employee { name managerId { name managerId { ... } } } }" with the given nesting. */
    private String nestedQuery(int managerLevels) {
        StringBuilder sb = new StringBuilder("{ employee { name");
        for (int i = 0; i < managerLevels; i++) {
            sb.append(" managerId { name");
        }
        sb.append(" }".repeat(managerLevels));
        sb.append(" } }");
        return sb.toString();
    }

    @Test
    void maxDepthZero_disablesEnforcement() {
        SqlCompiler compiler = new SqlCompiler(selfReferentialSchema(), "public", 30,
                testDialect, new NoOpMutationCompiler(), 0);
        // Deeply nested query compiles fine when the guard is disabled.
        assertNotNull(compiler.compile(nestedQuery(10)));
    }

    @Test
    void queryWithinDepthLimit_compiles() {
        SqlCompiler compiler = new SqlCompiler(selfReferentialSchema(), "public", 30,
                testDialect, new NoOpMutationCompiler(), 5);
        // A shallow query (well under the limit of 5) must compile.
        assertNotNull(compiler.compile(nestedQuery(1)));
    }

    @Test
    void queryExceedingDepthLimit_isRejected() {
        int limit = 3;
        SqlCompiler compiler = new SqlCompiler(selfReferentialSchema(), "public", 30,
                testDialect, new NoOpMutationCompiler(), limit);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(nestedQuery(10)));
        assertTrue(ex.getMessage().contains("exceeds maximum allowed depth of " + limit),
                "Expected depth-limit message, got: " + ex.getMessage());
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
