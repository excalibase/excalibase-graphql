package io.github.excalibase.rest.compiler;

import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrorException;
import io.github.excalibase.postgres.PostgresDialect;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** An embed hint that names no foreign key column is refused, not dropped (EXC-776, reported as EXC-842). */
class RestEmbedHintTest {

    private static final String POSTS = "public.posts";
    private static final String USERS = "public.users";

    private static SchemaInfo schema() {
        SchemaInfo schema = new SchemaInfo();
        for (String table : List.of(POSTS, USERS)) {
            schema.addColumn(table, "id", "integer");
            schema.addPrimaryKey(table, "id");
        }
        schema.addColumn(POSTS, "author_id", "integer");
        schema.addForeignKey(POSTS, "author_id", USERS, "id");
        return schema;
    }

    private static String sql(String table, String relation, String hint) {
        var compiler = new RestQueryCompiler(schema(), new PostgresDialect(), "public", 30);
        return compiler.compileSelect(new RestQueryCompiler.SelectQuery(table, List.of("id"), List.of(), List.of(),
                List.of(new RestQueryCompiler.EmbedSpec(relation, List.of("id"), hint)), List.of(), 10, 0, false)).sql();
    }

    @Test
    void anInnerHint_isRefused_onAForwardEmbed() {
        var refused = catchThrowableOfType(DataErrorException.class, () -> sql(POSTS, "users", "inner"));

        assertThat(refused.error().code()).isEqualTo(DataError.UNKNOWN_RELATIONSHIP_HINT);
    }

    @Test
    void anInnerHint_isRefused_onAReverseEmbed() {
        var refused = catchThrowableOfType(DataErrorException.class, () -> sql(USERS, "posts", "inner"));

        assertThat(refused.error().code()).isEqualTo(DataError.UNKNOWN_RELATIONSHIP_HINT);
    }

    @Test
    void aColumnHint_stillPicksTheKey() {
        assertThat(sql(POSTS, "users", "author_id")).contains("author_id");
    }

    @Test
    void anUnknownRelation_isStillSkipped() {
        assertThat(sql(POSTS, "nothing", "nope")).doesNotContain("nothing");
    }
}
