package io.github.excalibase.postgres;

import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A selection the schema does not have fails the request, wherever it is nested; nothing is dropped. */
class UnknownSelectionTest {

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
        return new SqlCompiler(info, "public", 30, new PostgresDialect(), new PostgresMutationCompiler());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ publicUsers { id secret } }",
            "{ publicPosts { id publicUserId { id secret } } }",
            "{ publicUsers { id publicPosts { secret } } }",
            "{ publicUsersConnection { edges { node { id secret } } } }",
            "{ publicUsersConnection { secret } }",
            "{ publicUsersAggregate { count secret } }",
            "{ publicUsers { ... on PublicUsers { secret } } }",
            "mutation { createPublicUsers(input: { id: 1 }) { id secret } }"
    })
    void anUnknownSelection_failsTheRequest(String query) {
        assertThatThrownBy(() -> compiler().compile(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown field(s): secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ publicUsers { __typename id name } }",
            "{ publicUsersConnection { __typename edges { __typename cursor node { id } } pageInfo { hasNextPage } } }"
    })
    void typenameAndKnownFields_compile(String query) {
        assertThatCode(() -> compiler().compile(query)).doesNotThrowAnyException();
        assertThat(compiler().compile(query).sql()).doesNotContain("__typename");
    }
}
