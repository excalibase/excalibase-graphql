package io.github.excalibase.schema;

import io.github.excalibase.spi.SchemaLoader;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What a loader hands over is merged except what belongs to a reserved schema, which is never served. */
class MultiSchemaLoaderTest {

    /** A loader that returns its reflection whole, internal schema included, whatever it was asked for. */
    private static final class HandsOverEverything implements SchemaLoader {

        @Override
        public void loadAll(JdbcTemplate jdbc, List<String> schemas, Map<String, SchemaInfo> perSchema) {
            SchemaInfo internal = new SchemaInfo();
            internal.setTableSchema("t", "excalibase_meta");
            internal.addColumn("t", "secret", "text");
            internal.addFunction("read_t", function("excalibase_meta", "read_t", "excalibase_meta.t"));
            perSchema.put("excalibase_meta", internal);

            SchemaInfo normal = new SchemaInfo();
            normal.setTableSchema("items", "public");
            normal.addColumn("items", "id", "integer");
            normal.addFunction("leak", function("public", "leak", "excalibase_meta.t"));
            normal.addFunction("plain", function("public", "plain", "public.items"));
            perSchema.put("public", normal);
        }

        private static SchemaInfo.FunctionInfo function(String schema, String name, String returnTable) {
            return new SchemaInfo.FunctionInfo(schema, name, SchemaInfo.RoutineKind.FUNCTION,
                    SchemaInfo.Volatility.STABLE, false, true, returnTable, List.of(), 1);
        }

        @Override public void loadColumns(JdbcTemplate jdbc, String schema, SchemaInfo info) {
            // not read: loadAll hands everything over
        }
        @Override public void loadForeignKeys(JdbcTemplate jdbc, String schema, SchemaInfo info) {
            // not read: loadAll hands everything over
        }
        @Override public void loadViews(JdbcTemplate jdbc, String schema, SchemaInfo info) {
            // not read: loadAll hands everything over
        }
        @Override public void loadFunctions(JdbcTemplate jdbc, String schema, SchemaInfo info) {
            // not read: loadAll hands everything over
        }
        @Override public void loadEnums(JdbcTemplate jdbc, String schema, SchemaInfo info) {
            // not read: loadAll hands everything over
        }
        @Override public void loadCompositeTypes(JdbcTemplate jdbc, String schema, SchemaInfo info) {
            // not read: loadAll hands everything over
        }
        @Override public void loadComputedFields(JdbcTemplate jdbc, String schema, SchemaInfo info) {
            // not read: loadAll hands everything over
        }
    }

    private SchemaInfo merged() {
        SchemaInfo target = new SchemaInfo();
        new MultiSchemaLoader(ReservedSchemas.fromConfig(null))
                .load(target, List.of("public", "excalibase_meta"), new HandsOverEverything(), null);
        return target;
    }

    @Test
    void aReservedSchemaOfTheLoader_isNotMerged() {
        SchemaInfo served = merged();

        assertThat(served.getTableNames()).containsExactly("public.items");
        assertThat(served.getFunctions()).doesNotContainKey("excalibase_meta.read_t");
    }

    @Test
    void aFunctionReturningRowsOfAReservedTable_isDropped_aNormalOneKept() {
        assertThat(merged().getFunctions()).containsOnlyKeys("public.plain");
    }
}
