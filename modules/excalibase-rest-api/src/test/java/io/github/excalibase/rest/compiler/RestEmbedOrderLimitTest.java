package io.github.excalibase.rest.compiler;

import io.github.excalibase.mysql.MysqlDialect;
import io.github.excalibase.postgres.PostgresDialect;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An embedded list is ordered and limited by its own parameters, as PostgREST does. (EXC-698) */
class RestEmbedOrderLimitTest {

    private static final String USERS = "public.users";
    private static final String POSTS = "public.posts";
    private static final String POSTS_RELATION = "posts";
    private static final String AUTHOR_ID = "author_id";
    private static final String PUBLIC = "public";
    private static final List<RestQueryCompiler.OrderBySpec> BY_ID_DESC =
            List.of(new RestQueryCompiler.OrderBySpec("id", "DESC", null));

    private static SchemaInfo schema() {
        SchemaInfo schema = new SchemaInfo();
        for (String table : List.of(USERS, POSTS)) {
            schema.addColumn(table, "id", "integer");
            schema.addPrimaryKey(table, "id");
            schema.setTableSchema(table, PUBLIC);
        }
        schema.addColumn(POSTS, AUTHOR_ID, "integer");
        schema.addForeignKey(POSTS, AUTHOR_ID, USERS, "id");
        return schema;
    }

    private static String sql(String table, RestQueryCompiler.EmbedSpec embed) {
        return sql(table, embed, new RestQueryCompiler(schema(), new PostgresDialect(), PUBLIC, 30));
    }

    private static String mysqlSql(String table, RestQueryCompiler.EmbedSpec embed) {
        return sql(table, embed, new RestQueryCompiler(schema(), new MysqlDialect(), PUBLIC, 30));
    }

    private static String sql(String table, RestQueryCompiler.EmbedSpec embed, RestQueryCompiler compiler) {
        return compiler.compileSelect(new RestQueryCompiler.SelectQuery(table, List.of("id"), List.of(), List.of(),
                List.of(embed), List.of(), 10, 0, false)).sql();
    }

    private static RestQueryCompiler.EmbedSpec posts(List<RestQueryCompiler.OrderBySpec> order, Integer limit) {
        return new RestQueryCompiler.EmbedSpec(POSTS_RELATION, List.of("id"), null, List.of(), null, order, limit);
    }

    @Test
    void anEmbeddedListIsOrdered() {
        assertThat(sql(USERS, posts(BY_ID_DESC, null))).containsPattern("ORDER BY .*\"id\" DESC");
    }

    @Test
    void anEmbeddedListIsLimited() {
        assertThat(sql(USERS, posts(List.of(), 2))).contains("LIMIT 2");
    }

    @Test
    void anOrderWithNoLimit_stillCarriesALimit_becauseMysqlDropsAnOrderedSubqueryWithoutOne() {
        assertThat(sql(USERS, posts(BY_ID_DESC, null))).containsPattern("ORDER BY .*\"id\" DESC LIMIT \\d+");
        assertThat(mysqlSql(USERS, posts(BY_ID_DESC, null))).containsPattern("ORDER BY .*`id` DESC LIMIT \\d+");
    }

    @Test
    void aTopLevelOrderColumnTheTableLacks_isRefusedToo() {
        var compiler = new RestQueryCompiler(schema(), new PostgresDialect(), PUBLIC, 30);
        var order = List.of(new RestQueryCompiler.OrderBySpec("nope", "ASC", null));
        var query = new RestQueryCompiler.SelectQuery(USERS, List.of("id"), List.of(), List.of(), List.of(), order,
                10, 0, false);

        assertThatThrownBy(() -> compiler.compileSelect(query))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
    }

    @Test
    void aLimitOfZeroEmbedsNoRows() {
        assertThat(sql(USERS, posts(List.of(), 0))).contains("LIMIT 0");
    }

    @Test
    void aLimitAboveTheRowCapIsCut() {
        var capped = new RestQueryCompiler(schema(), new PostgresDialect(), PUBLIC, 30, TableAccess.UNRESTRICTED, true);

        assertThat(sql(USERS, posts(List.of(), 1000), capped)).contains("LIMIT 30").doesNotContain("LIMIT 1000");
    }

    @Test
    void anOrderColumnTheTableLacks_isRefused() {
        var order = List.of(new RestQueryCompiler.OrderBySpec("nope", "ASC", null));

        var embed = posts(order, null);

        assertThatThrownBy(() -> sql(USERS, embed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
    }

    @Test
    void aNestedEmbedKeepsItsOwnOrder() {
        var nested = posts(BY_ID_DESC, 1);
        var outer = new RestQueryCompiler.EmbedSpec("users", List.of("id"), null, List.of(nested), null, List.of(), null);

        assertThat(sql(POSTS, outer)).contains("LIMIT 1");
    }
}
