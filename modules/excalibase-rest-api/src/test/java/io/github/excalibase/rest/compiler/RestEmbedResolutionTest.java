package io.github.excalibase.rest.compiler;

import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrorException;
import io.github.excalibase.postgres.PostgresDialect;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Which relationship an embed names: one, a schema-qualified one, or an answer saying it cannot tell. (EXC-776) */
class RestEmbedResolutionTest {

    private static final String DOCS = "a.docs";
    private static final String USERS_TABLE = "a.users";
    private static final String TEAMS_TABLE = "a.teams";

    private static SchemaInfo schema() {
        SchemaInfo schema = new SchemaInfo();
        for (String table : List.of(DOCS, "a.mixed", "a.users", "b.users", "a.logs", "b.logs")) {
            schema.addColumn(table, "id", "integer");
            schema.addColumn(table, "name", "text");
            schema.setTableSchema(table, table.substring(0, 1));
            schema.addPrimaryKey(table, "id");
        }
        schema.addColumn(DOCS, "created_by", "integer");
        schema.addColumn(DOCS, "updated_by", "integer");
        schema.addColumn("a.logs", "user_id", "integer");
        schema.addColumn("b.logs", "user_id", "integer");
        schema.addForeignKey(DOCS, "created_by", "a.users", "id");
        schema.addForeignKey(DOCS, "updated_by", "a.users", "id");
        schema.addColumn("a.mixed", "a_user", "integer");
        schema.addColumn("a.mixed", "created_by", "integer");
        schema.addColumn("a.mixed", "b_user", "integer");
        schema.addForeignKey("a.mixed", "a_user", "a.users", "id");
        schema.addForeignKey("a.mixed", "created_by", "a.users", "id");
        schema.addForeignKey("a.mixed", "b_user", "b.users", "id");
        schema.addForeignKey("a.logs", "user_id", "a.users", "id");
        schema.addForeignKey("b.logs", "user_id", "a.users", "id");
        return schema;
    }

    private static SchemaInfo mutualSchema() {
        SchemaInfo schema = new SchemaInfo();
        for (String table : List.of(USERS_TABLE, TEAMS_TABLE)) {
            schema.addColumn(table, "id", "integer");
            schema.setTableSchema(table, "a");
            schema.addPrimaryKey(table, "id");
        }
        schema.addColumn(USERS_TABLE, "team_id", "integer");
        schema.addColumn(TEAMS_TABLE, "owner_id", "integer");
        schema.addForeignKey(USERS_TABLE, "team_id", TEAMS_TABLE, "id");
        schema.addForeignKey(TEAMS_TABLE, "owner_id", USERS_TABLE, "id");
        return schema;
    }

    private static String mutualSql(String table, String relation, String hint) {
        var compiler = new RestQueryCompiler(mutualSchema(), new PostgresDialect(), "a", 30);
        return compiler.compileSelect(new RestQueryCompiler.SelectQuery(table, List.of("id"), List.of(), List.of(),
                List.of(new RestQueryCompiler.EmbedSpec(relation, List.of("id"), hint)), List.of(), 10, 0, false)).sql();
    }

    @Test
    void mutualKeys_aHintThatFitsOnlyTheOtherDirection_picksIt() {
        assertThat(mutualSql(USERS_TABLE, "teams", "owner_id")).contains("owner_id");
        assertThat(mutualSql(TEAMS_TABLE, "users", "team_id")).contains("team_id");
        assertThat(mutualSql(USERS_TABLE, "teams", "team_id")).contains("team_id");
    }

    @Test
    void mutualKeys_aHintThatFitsNeitherDirection_isStillRefused() {
        var refused = catchThrowableOfType(DataErrorException.class, () -> mutualSql(USERS_TABLE, "teams", "nope"));

        assertThat(refused.error().code()).isEqualTo(DataError.UNKNOWN_RELATIONSHIP_HINT);
    }

    private static String sql(String table, String relation, String hint) {
        var compiler = new RestQueryCompiler(schema(), new PostgresDialect(), "a", 30);
        return compiler.compileSelect(new RestQueryCompiler.SelectQuery(table, List.of("id"), List.of(), List.of(),
                List.of(new RestQueryCompiler.EmbedSpec(relation, List.of("id"), hint)), List.of(), 10, 0, false)).sql();
    }

    private static DataError refusal(String table, String relation, String hint) {
        return catchThrowableOfType(DataErrorException.class,
                () -> sql(table, relation, hint)).error();
    }

    @Test
    void twoKeysToOneTable_withoutAHint_areAmbiguous_andNameTheColumnHints() {
        DataError error = refusal(DOCS, "users", null);

        assertThat(error.code()).isEqualTo(DataError.AMBIGUOUS_RELATIONSHIP);
        assertThat(error.message()).contains("users!created_by", "users!updated_by");
    }

    @Test
    void sameNamedChildrenOfTwoSchemas_areAmbiguous_andNameTheQualifiedTables() {
        DataError error = refusal("a.users", "logs", "user_id");

        assertThat(error.code()).isEqualTo(DataError.AMBIGUOUS_RELATIONSHIP);
        assertThat(error.message()).contains("a.logs", "b.logs");
    }

    @Test
    void candidatesAcrossTables_listAKeyedNameForATableWithSeveralKeys_andTheBareQualifiedNameOtherwise() {
        DataError error = refusal("a.mixed", "users", null);

        assertThat(error.message()).contains("a.users!a_user", "a.users!created_by", "b.users")
                .doesNotContain("use one of: a.users,");
    }

    @Test
    void aQualifiedEmbed_comesBackUnderItsTableName() {
        assertThat(sql("a.users", "b.logs", null)).contains("'logs'").doesNotContain("'b.logs'");
    }

    @Test
    void aQualifiedNamePicksOneSchemasTable() {
        assertThat(sql("a.users", "b.logs", null)).contains("\"b\".\"logs\"");
    }

    @Test
    void aColumnHintPicksOneKey() {
        assertThat(sql(DOCS, "users", "updated_by")).contains("updated_by");
    }

    @Test
    void aHintNamingNoColumn_isRefused_whileAnUnknownRelationIsStillSkipped() {
        assertThat(refusal(DOCS, "users", "nope").code()).isEqualTo(DataError.UNKNOWN_RELATIONSHIP_HINT);
        assertThat(sql(DOCS, "nothing", "nope")).doesNotContain("nothing");
    }

    @Test
    void anAlias_isTheKeyTheEmbedComesBackUnder() {
        var compiler = new RestQueryCompiler(schema(), new PostgresDialect(), "a", 30);
        String sql = compiler.compileSelect(new RestQueryCompiler.SelectQuery(DOCS, List.of("id"), List.of(), List.of(),
                List.of(new RestQueryCompiler.EmbedSpec("users", List.of("id"), "created_by", List.of(), "creator"),
                        new RestQueryCompiler.EmbedSpec("users", List.of("id"), "updated_by", List.of(), "updater")),
                List.of(), 10, 0, false)).sql();

        assertThat(sql).contains("'creator'", "'updater'");
    }

    @Test
    void twoEmbedsUnderOneKey_areRefused() {
        var compiler = new RestQueryCompiler(schema(), new PostgresDialect(), "a", 30);
        var query = new RestQueryCompiler.SelectQuery(DOCS, List.of("id"), List.of(), List.of(),
                List.of(new RestQueryCompiler.EmbedSpec("users", List.of("id"), "created_by"),
                        new RestQueryCompiler.EmbedSpec("users", List.of("id"), "updated_by")),
                List.of(), 10, 0, false);

        DataErrorException refused = catchThrowableOfType(DataErrorException.class,
                () -> compiler.compileSelect(query));

        assertThat(refused.error().code()).isEqualTo(DataError.EMBED_KEY_COLLISION);
    }
}
