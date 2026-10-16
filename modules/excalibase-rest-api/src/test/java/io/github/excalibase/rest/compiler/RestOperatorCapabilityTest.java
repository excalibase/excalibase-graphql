package io.github.excalibase.rest.compiler;

import io.github.excalibase.FilterCapabilities;
import io.github.excalibase.postgres.PostgresDialect;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A REST operator the database cannot run is refused, never compiled into SQL it would reject or skip. */
class RestOperatorCapabilityTest {

    private static final String TABLE = "public.docs";

    private static RestQueryCompiler compiler(FilterCapabilities capabilities) {
        SchemaInfo schema = new SchemaInfo();
        schema.addColumn(TABLE, "id", "integer");
        schema.addColumn(TABLE, "title", "text");
        schema.addColumn(TABLE, "embedding", "vector");
        schema.addPrimaryKey(TABLE, "id");
        schema.setTableSchema(TABLE, "public");
        PostgresDialect dialect = new PostgresDialect() {
            @Override
            public FilterCapabilities filterCapabilities() {
                return capabilities;
            }
        };
        return new RestQueryCompiler(schema, dialect, "public", 30);
    }

    private static RestQueryCompiler.FilterSpec filter(String column, String operator, String value) {
        return new RestQueryCompiler.FilterSpec(column, operator, value, false);
    }

    private static RestQueryCompiler.SelectQuery select(List<RestQueryCompiler.FilterSpec> filters,
                                                        List<RestQueryCompiler.OrCondition> ors) {
        return new RestQueryCompiler.SelectQuery(TABLE, List.of("*"), filters, ors, List.of(), List.of(), 10, 0, false);
    }

    @Test
    void anOperatorTheDatabaseCannotRunIsRefusedOnEveryPath() {
        RestQueryCompiler portable = compiler(FilterCapabilities.PORTABLE);
        List<RestQueryCompiler.FilterSpec> regex = List.of(filter("title", "match", "^a"));

        assertThatThrownBy(() -> portable.compileSelect(select(regex, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'match'");
        assertThatThrownBy(() -> portable.compileSelect(select(List.of(),
                List.of(new RestQueryCompiler.OrCondition(List.of(filter("title", "fts", "fox")))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'fts'");
        assertThatThrownBy(() -> portable.compileDelete(TABLE, regex))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> portable.compileUpdate(TABLE, Map.of("title", "x"), regex))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aVectorSearchTheDatabaseCannotRunIsRefusedRatherThanDropped() {
        assertThatThrownBy(() -> compiler(FilterCapabilities.PORTABLE).compileSelect(select(
                List.of(filter("embedding", "vector", "{\"near\":[1,2]}")), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'vector'");
    }

    @Test
    void postgresStillRunsEveryOperator() {
        assertThat(compiler(FilterCapabilities.ALL).compileSelect(select(List.of(filter("title", "match", "^a")),
                List.of())).sql()).contains("~");
    }
}
