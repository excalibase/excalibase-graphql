package io.github.excalibase.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RelationshipAliasesTest {

    private static SchemaInfo sports() {
        SchemaInfo schema = new SchemaInfo();
        schema.addColumn("public.teams", "id", "bigint");
        schema.addColumn("public.matches", "id", "bigint");
        for (String column : List.of("home_team_id", "away_team_id", "winner_team_id")) {
            schema.addColumn("public.matches", column, "bigint");
            schema.addForeignKey("public.matches", column, "public.teams", "id");
        }
        return schema;
    }

    @Test
    void anObjectRelationship_isAlsoNamedWithoutItsId() {
        SchemaInfo schema = sports();

        assertThat(schema.getRelationshipAlias("public.matches", "publicHomeTeamId")).isEqualTo("publicHomeTeam");
        assertThat(schema.getForwardFk("public.matches", "publicHomeTeam"))
                .isSameAs(schema.getForwardFk("public.matches", "publicHomeTeamId"));
    }

    @Test
    void everyArrayRelationshipFromTheSameTable_isAlsoNamedByItsKey() {
        SchemaInfo schema = sports();

        for (String key : List.of("HomeTeam", "AwayTeam", "WinnerTeam")) {
            assertThat(schema.getReverseFk("public.teams", "publicMatchesBy" + key))
                    .as(key).isNotNull()
                    .extracting(SchemaInfo.ReverseFkInfo::fkColumn).isEqualTo(NamingUtils.camelToSnakeCase(key) + "_id");
        }
    }

    @Test
    void aSingleArrayRelationship_keepsItsOnlyName() {
        SchemaInfo schema = new SchemaInfo();
        schema.addColumn("public.goals", "match_id", "bigint");
        schema.addForeignKey("public.goals", "match_id", "public.matches", "id");

        assertThat(schema.getRelationshipAlias("public.matches", "publicGoals")).isNull();
        assertThat(schema.getRelationshipAlias("public.goals", "publicMatchId")).isEqualTo("publicMatch");
    }

    @Test
    void anAliasThatWouldClash_isNotGiven() {
        SchemaInfo schema = new SchemaInfo();
        schema.addColumn("orders", "customer_id", "bigint");
        schema.addColumn("orders", "customer", "text");
        schema.addForeignKey("orders", "customer_id", "customers", "id");
        schema.addColumn("lines", "owner", "bigint");
        schema.addColumn("lines", "owner_id", "bigint");
        schema.addForeignKey("lines", "owner", "users", "id");
        schema.addForeignKey("lines", "owner_id", "users", "id");

        assertThat(schema.getRelationshipAlias("orders", "customerId")).as("a column wins").isNull();
        assertThat(schema.getRelationshipAlias("lines", "ownerId")).as("an existing relationship wins").isNull();
        assertThat(schema.getForwardFk("lines", "owner").fkColumn()).isEqualTo("owner");
    }

    @Test
    void aCamelCaseKey_andACompositeKey() {
        SchemaInfo schema = new SchemaInfo();
        schema.addColumn("posts", "authorId", "bigint");
        schema.addForeignKey("posts", "authorId", "users", "id");
        schema.addCompositeForeignKey("lines", List.of("order_id", "line_no"), "orders", List.of("id", "line_no"));

        assertThat(schema.getRelationshipAlias("posts", "authorId")).isEqualTo("author");
        assertThat(schema.getRelationshipAlias("lines", "orders")).isNull();
    }
}
