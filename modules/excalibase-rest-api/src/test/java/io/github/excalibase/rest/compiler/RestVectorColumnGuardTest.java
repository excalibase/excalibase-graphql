package io.github.excalibase.rest.compiler;

import io.github.excalibase.postgres.PostgresDialect;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The column of a vector search is the URL key; a different one inside the JSON is refused, never ordered by. */
class RestVectorColumnGuardTest {

    private static final String TABLE = "public.docs";

    private static RestQueryCompiler compiler() {
        SchemaInfo schema = new SchemaInfo();
        schema.addColumn(TABLE, "id", "integer");
        schema.addColumn(TABLE, "embedding", "vector");
        schema.setTableSchema(TABLE, "public");
        schema.addExtension("vector", "0.7");
        return new RestQueryCompiler(schema, new PostgresDialect(), "public", 30);
    }

    private static RestQueryCompiler.SelectQuery select(String column, String json) {
        return new RestQueryCompiler.SelectQuery(TABLE, List.of("*"),
                List.of(new RestQueryCompiler.FilterSpec(column, "vector", json, false)),
                List.of(), List.of(), List.of(), 10, 0, false);
    }

    @Test
    void aJsonColumnOtherThanTheUrlKeyIsRefused() {
        assertThatThrownBy(() -> compiler().compileSelect(select("embedding",
                "{\"column\":\"secret_embedding\",\"near\":[1,2]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret_embedding");
    }

    @Test
    void aJsonColumnEqualToTheUrlKeyOrAbsentStillSearches() {
        assertThat(compiler().compileSelect(select("embedding", "{\"column\":\"embedding\",\"near\":[1,2]}")).sql())
                .contains("\"embedding\"");
        assertThat(compiler().compileSelect(select("embedding", "{\"near\":[1,2]}")).sql())
                .contains("\"embedding\"");
    }
}
