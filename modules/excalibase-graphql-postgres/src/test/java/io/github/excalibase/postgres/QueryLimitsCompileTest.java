package io.github.excalibase.postgres;

import graphql.parser.Parser;
import io.github.excalibase.compiler.QueryRejectedException;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.compiler.SqlKeywords;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** EXC-710 / EXC-725: an abusive query shape is refused before SQL is built; nested lists are capped. */
class QueryLimitsCompileTest {

    private static SqlCompiler compiler() {
        SchemaInfo info = new SchemaInfo();
        info.setTableSchema("public.users", "public");
        info.addColumn("public.users", "id", "integer");
        info.addColumn("public.users", "name", "text");
        info.addPrimaryKey("public.users", "id");
        info.setTableSchema("public.posts", "public");
        info.addColumn("public.posts", "id", "integer");
        info.addColumn("public.posts", "user_id", "integer");
        info.addPrimaryKey("public.posts", "id");
        info.addForeignKey("public.posts", "user_id", "public.users", "id");
        return new SqlCompiler(info, "public", 30, new PostgresDialect(), new PostgresMutationCompiler(), 15);
    }

    @Test
    void aFragmentThatSpreadsItselfIsRefusedNotAStackOverflow() {
        String query = "{ publicUsers { ...A } } fragment A on PublicUsers { id ...A }";
        assertThatThrownBy(() -> compiler().compile(Parser.parse(query)))
                .isInstanceOf(QueryRejectedException.class).hasMessageContaining("spreads itself");
    }

    @Test
    void aCycleThroughSeveralFragmentsIsRefused() {
        String query = "{ publicUsers { ...A } } fragment A on PublicUsers { ...B } fragment B on PublicUsers { ...C } "
                + "fragment C on PublicUsers { id ...A }";
        assertThatThrownBy(() -> compiler().compile(Parser.parse(query)))
                .isInstanceOf(QueryRejectedException.class);
    }

    @Test
    void aFragmentChainThatDoublesAtEveryStepIsRefusedAtOnce() {
        StringBuilder query = new StringBuilder("{ publicUsers { ...F0 } } ");
        for (int level = 0; level < 26; level++) {
            query.append("fragment F").append(level).append(" on PublicUsers { id ");
            if (level < 25) query.append("...F").append(level + 1).append(" ...F").append(level + 1);
            query.append(" } ");
        }
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> compiler().compile(Parser.parse(query.toString())))
                        .isInstanceOf(QueryRejectedException.class).hasMessageContaining("fields"));
    }

    @Test
    void thousandsOfAliasesAreRefused() {
        String aliases = IntStream.range(0, 2000).mapToObj(i -> "a" + i + ": id").collect(Collectors.joining(" "));
        assertThatThrownBy(() -> compiler().compile(Parser.parse("{ publicUsers { " + aliases + " } }")))
                .isInstanceOf(QueryRejectedException.class);
    }

    @Test
    void anOrdinaryQueryWithSharedFragmentsStillCompiles() {
        String query = "{ publicUsers { ...U publicPosts { id } } other: publicUsers { ...U } } "
                + "fragment U on PublicUsers { id name }";
        assertThat(compiler().compile(Parser.parse(query)).sql()).isNotBlank();
    }

    @Test
    void aDepthOverTheLimitIsStillTheEarlierError() {
        StringBuilder deep = new StringBuilder("{ publicUsers { ");
        for (int level = 0; level < 8; level++) deep.append("publicPosts { publicUserId { ");
        deep.append("id").append(" } }".repeat(8)).append(" } }");
        assertThatThrownBy(() -> compiler().compile(Parser.parse(deep.toString())))
                .isNotInstanceOf(QueryRejectedException.class)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds maximum allowed depth");
    }

    @Test
    void aNestedListWithoutALimitIsCappedAtMaxRows() {
        var compiled = compiler().compile(Parser.parse("{ publicUsers { id publicPosts { id } } }"));
        long caps = compiled.params().values().stream().filter(value -> Integer.valueOf(30).equals(value)).count();
        assertThat(caps).as("top-level and nested lists both carry the 30 row cap: " + compiled.params()).isEqualTo(2);
    }

    @Test
    void aNestedLimitAboveMaxRowsIsLowered() {
        var compiled = compiler().compile(Parser.parse("{ publicUsers { id publicPosts(limit: 100000) { id } } }"));
        assertThat(compiled.params().values()).doesNotContain(100000);
    }

    @Test
    void aColumnNameCannotSplitAParameterName() {
        assertThat(SqlKeywords.namedParam("p_", "first name; DROP", 3)).matches("[A-Za-z0-9_]+");
    }
}
