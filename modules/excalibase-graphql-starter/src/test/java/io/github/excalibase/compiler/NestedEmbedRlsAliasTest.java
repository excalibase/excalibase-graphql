package io.github.excalibase.compiler;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.security.RlsContext;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RlsWhereContributor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit M7: a nested FK embed aliases the related table (`FROM t <subAlias>`), so
 * the RLS predicate spliced into that sub-select must correlate to {@code subAlias}
 * — a bare table-name reference (the old {@code alias=null} path) breaks
 * relationship/EXISTS policies with "missing FROM-clause entry" under aliasing.
 */
class NestedEmbedRlsAliasTest {

    @AfterEach
    void clearRls() {
        RlsContext.clear();
    }

    /** Tags each RLS splice as {@code RLS_<table>@<alias>} so the alias is observable in the SQL. */
    private void installAliasTaggingContributor() {
        RlsContext.set(new RlsWhereContributor() {
            @Override public RlsWhereContributor.Contribution contribute(String table, RlsOp op) {
                return new RlsWhereContributor.Contribution("RLS_" + table + "@null", Map.of());
            }
            @Override public RlsWhereContributor.Contribution contribute(String table, String alias, RlsOp op) {
                return new RlsWhereContributor.Contribution("RLS_" + table + "@" + alias, Map.of());
            }
        });
    }

    private SchemaInfo schemaWithFk() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("public.users", "id", "integer");
        info.addColumn("public.users", "name", "varchar");
        info.addPrimaryKey("public.users", "id");
        info.setTableSchema("public.users", "public");

        info.addColumn("sales.orders", "id", "integer");
        info.addColumn("sales.orders", "user_id", "integer");
        info.addColumn("sales.orders", "total", "numeric");
        info.addPrimaryKey("sales.orders", "id");
        info.setTableSchema("sales.orders", "sales");
        info.addForeignKey("sales.orders", "user_id", "public.users", "id");
        return info;
    }

    @Test
    @DisplayName("forward FK embed correlates the related table's RLS to the sub-select alias, not null")
    void forwardEmbed_rlsCorrelatesToSubAlias() {
        installAliasTaggingContributor();
        SqlCompiler compiler = new SqlCompiler(schemaWithFk(), "public", 30, new TestDialect(), new NoOpMutationCompiler());

        SqlCompiler.CompiledQuery result = compiler.compile(
                "{ salesOrders { id total salesUserId { id name } } }");
        String sql = result.sql();

        // Top-level RLS for sales.orders is present, and the embed's RLS for
        // public.users must carry a real alias — never @null (the M7 bug).
        assertTrue(sql.contains("RLS_public.users@"), "embed RLS missing: " + sql);
        assertFalse(sql.contains("RLS_public.users@null"),
                "embed RLS must correlate to the sub-select alias, not null: " + sql);
    }

    private static class NoOpMutationCompiler implements MutationCompiler {
        @Override
        public SqlCompiler.CompiledQuery compileMutation(
                graphql.language.Field field, String fieldName,
                Map<String, Object> params, Map<String, Object> variables,
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
        @Override public String randAlias() { return "\"sub" + Math.abs(System.nanoTime() % 100000) + "\""; }
        @Override public String distinctOn(List<String> cols, String alias) { return ""; }
    }
}
