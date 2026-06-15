package io.github.excalibase.compiler;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.MutationCompiler;
import graphql.language.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards distinctOn column validation: only real columns may flow into the
 * SQL, so an unknown name is rejected rather than quoted and echoed back as an
 * error oracle.
 */
class DistinctOnValidationTest {

    private SchemaInfo schemaInfo;
    private SqlCompiler compiler;

    @BeforeEach
    void setUp() {
        schemaInfo = new SchemaInfo();
        schemaInfo.addColumn("users", "id", "integer");
        schemaInfo.addColumn("users", "name", "varchar");
        schemaInfo.addColumn("users", "email", "varchar");
        schemaInfo.addPrimaryKey("users", "id");
        compiler = new SqlCompiler(schemaInfo, "public", 30, new TestDialect(), new NoOpMutationCompiler());
    }

    @Test
    @DisplayName("distinctOn on a real column compiles successfully")
    void knownColumn_compiles() {
        SqlCompiler.CompiledQuery result =
                compiler.compile("{ users(distinctOn: [\"name\"]) { id name } }");
        assertThat(result).isNotNull();
        assertThat(result.sql()).contains("\"users\"");
    }

    @Test
    @DisplayName("distinctOn on an unknown column throws IllegalArgumentException")
    void unknownColumn_throws() {
        assertThatThrownBy(() ->
                compiler.compile("{ users(distinctOn: [\"' OR 1=1 --\"]) { id name } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown column in distinctOn");
    }

    @Test
    @DisplayName("distinctOn mixing a real and an unknown column is rejected")
    void mixedKnownAndUnknown_throws() {
        assertThatThrownBy(() ->
                compiler.compile("{ users(distinctOn: [\"name\", \"bogus\"]) { id name } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
    }

    /** Postgres-like quoting dialect, sufficient for compileList. */
    private static final class TestDialect implements SqlDialect {
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
        @Override public String distinctOn(List<String> cols, String alias) {
            StringBuilder sb = new StringBuilder("DISTINCT ON (");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(alias).append(".\"").append(cols.get(i)).append("\"");
            }
            return sb.append(")").toString();
        }
    }

    /** Mutation compiler that does nothing — this test only exercises queries. */
    private static final class NoOpMutationCompiler implements MutationCompiler {
        @Override
        public SqlCompiler.CompiledQuery compileMutation(Field field, String fieldName,
                Map<String, Object> params, Map<String, Object> variables, MutationBuilder shared) {
            return null;
        }
        @Override
        public SqlCompiler.CompiledQuery compileMutationFragment(Field field, String fieldName,
                Map<String, Object> params, Map<String, Object> variables, MutationBuilder shared) {
            return null;
        }
    }
}
