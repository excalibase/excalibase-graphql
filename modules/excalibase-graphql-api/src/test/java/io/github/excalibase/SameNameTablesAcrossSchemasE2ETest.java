package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Two schemas each own a table named users. A foreign key binds to the table in the schema it names, over
 * GraphQL and over REST, whichever schema the referencing table is in. (EXC-776)
 */
class SameNameTablesAcrossSchemasE2ETest extends EngineIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-same-name-schemas.sql");

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String REST = "/test-proj/api/v1";

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.datasource(postgres);
        properties.put("app.project-id", "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("app.database-type", "postgres");
        return properties;
    }

    private String graphql(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    @Test
    void graphql_keyInsideSchemaA_bindsToSchemaAUsers() throws Exception {
        http.post("/test-proj/graphql", graphql("{ tenantAPosts { tenantAUserId { name } } }"),
                        "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.tenantAPosts[0].tenantAUserId.name", is("from-a")));
    }

    @Test
    void graphql_keyInsideSchemaB_bindsToSchemaBUsers() throws Exception {
        http.post("/test-proj/graphql", graphql("{ tenantBNotes { tenantBUserId { name } } }"),
                        "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.tenantBNotes[0].tenantBUserId.name", is("from-b")));
    }

    @Test
    void graphql_keyFromSchemaBIntoSchemaA_bindsToSchemaAUsers_notItsOwnSameNamedTable() throws Exception {
        http.post("/test-proj/graphql", graphql("{ tenantBOrders { tenantBUserId { name } } }"),
                        "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.tenantBOrders[0].tenantBUserId.name", is("from-a")));
    }

    @Test
    void graphql_reverseSideOfTheCrossSchemaKey_isOnSchemaAUsers_notSchemaBUsers() throws Exception {
        http.post("/test-proj/graphql", graphql("{ tenantAUsers { tenantBOrders { id } } }"),
                        "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.tenantAUsers[0].tenantBOrders[0].id", is(1)));
    }

    @Test
    void rest_embedOfKeyInsideSchemaA_resolvesToSchemaAUsers() {
        http.get(REST + "/posts?select=id,users(name)", "Accept-Profile", "tenant_a")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].users.name", is("from-a")));
    }

    @Test
    void rest_embedOfKeyFromSchemaBIntoSchemaA_resolvesToSchemaAUsers() {
        http.get(REST + "/orders?select=id,users(name)", "Accept-Profile", "tenant_b")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].users.name", is("from-a")));
    }

    @Test
    void rest_forwardEmbedNamingTwoSameNamedTargets_isAmbiguous_notTheFirstOne() {
        http.get(REST + "/audit?select=id,users(name)", "Accept-Profile", "tenant_a")
                .expect(status().is(300))
                .expect(jsonPath("$.code", is("ambiguous_relationship")));
    }

    @Test
    void rest_forwardEmbedWithAKeyHint_picksThatKeysTarget() {
        http.get(REST + "/audit?select=id,users!b_user(name)", "Accept-Profile", "tenant_a")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].users.name", is("from-b")));
        http.get(REST + "/audit?select=id,users!a_user(name)", "Accept-Profile", "tenant_a")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].users.name", is("from-a")));
    }

    @Test
    void rest_reverseEmbedNamingTwoSameNamedChildren_isAmbiguous_notTheFirstOne() {
        http.get(REST + "/users?select=id,logs(id)", "Accept-Profile", "tenant_a")
                .expect(status().is(300))
                .expect(jsonPath("$.code", is("ambiguous_relationship")));
    }

    @Test
    void rest_reverseEmbedQualifiedBySchema_picksThatSchemasTable() {
        http.get(REST + "/users?select=id,tenant_b.logs(id)", "Accept-Profile", "tenant_a")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].logs[0].id", is(1)));
    }

    @Test
    void rest_forwardEmbedQualifiedBySchema_picksThatSchemasTable() {
        http.get(REST + "/audit?select=id,tenant_b.users(name)", "Accept-Profile", "tenant_a")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].users.name", is("from-b")));
    }

    @Test
    void rest_ambiguousAnswer_suggestsHintsThatWork() {
        http.get(REST + "/users?select=id,logs(id)", "Accept-Profile", "tenant_a")
                .expect(status().is(300))
                .expect(jsonPath("$.message", containsString("tenant_a.logs")))
                .expect(jsonPath("$.message", containsString("tenant_b.logs")));
    }

    @Test
    void rest_twoKeysToTheSameTable_withoutAHint_areAmbiguous_notTheFirstOne() {
        http.get(REST + "/docs?select=id,users(name)", "Accept-Profile", "tenant_a")
                .expect(status().is(300))
                .expect(jsonPath("$.code", is("ambiguous_relationship")))
                .expect(jsonPath("$.message", containsString("users!created_by")))
                .expect(jsonPath("$.message", containsString("users!updated_by")));
        http.get(REST + "/docs?select=id,users!updated_by(name)", "Accept-Profile", "tenant_a")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].users.name", is("from-a")));
    }

    @Test
    void rest_hintNamingNoColumn_is400_notASilentlyDroppedEmbed() {
        http.get(REST + "/docs?select=id,users!nope(name)", "Accept-Profile", "tenant_a")
                .expect(status().isBadRequest())
                .expect(jsonPath("$.code", is("unknown_relationship_hint")));
    }

    @Test
    void rest_mixedCandidates_listOnlyNamesThatWork_andEveryOneReturns200() throws Exception {
        String body = http.get(REST + "/mixed?select=id,users(name)", "Accept-Profile", "tenant_a")
                .expect(status().is(300)).body();
        String message = mapper.readTree(body).path("message").asText();
        String[] options = message.substring(message.indexOf("use one of: ") + "use one of: ".length()).split(", ");

        org.assertj.core.api.Assertions.assertThat(options).containsExactlyInAnyOrder(
                "tenant_a.users!a_user", "tenant_a.users!created_by", "tenant_b.users");
        for (String option : options) {
            http.get(REST + "/mixed?select=id," + option + "(name)", "Accept-Profile", "tenant_a")
                    .expect(status().isOk());
        }
    }

    @Test
    void rest_embedsOfOneTableInTwoSchemas_keepTheirWrittenKeys_soNeitherOverwritesTheOther() {
        http.get(REST + "/audit?select=id,tenant_a.users!a_user(name),tenant_b.users!b_user(name)",
                        "Accept-Profile", "tenant_a")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0]['tenant_a.users'].name", is("from-a")))
                .expect(jsonPath("$.data[0]['tenant_b.users'].name", is("from-b")));
    }

    @Test
    void rest_aliasedEmbeds_comeBackUnderTheirAliases() {
        http.get(REST + "/docs?select=id,creator:users!created_by(name),updater:users!updated_by(name)",
                        "Accept-Profile", "tenant_a")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].creator.name", is("from-a")))
                .expect(jsonPath("$.data[0].updater.name", is("from-a")));
    }

    @Test
    void rest_twoEmbedsUnderOneKey_are400_notOneSilentlyDropped() {
        http.get(REST + "/docs?select=id,users!created_by(name),users!updated_by(name)", "Accept-Profile", "tenant_a")
                .expect(status().isBadRequest())
                .expect(jsonPath("$.code", is("embed_key_collision")))
                .expect(jsonPath("$.message", containsString("alias")));
    }

    @Test
    void rest_aliasWithAQuoteOrBackslash_is400_beforeAnySqlIsBuilt() {
        http.get(REST + "/docs?select=id,a%5C:users!created_by(name)", "Accept-Profile", "tenant_a")
                .expect(status().isBadRequest());
        http.get(REST + "/docs?select=id,a%27b:users!created_by(name)", "Accept-Profile", "tenant_a")
                .expect(status().isBadRequest());
        http.get(REST + "/docs?select=id,:users!created_by(name)", "Accept-Profile", "tenant_a")
                .expect(status().isBadRequest());
        http.get(REST + "/docs?select=id,__proto__:users!created_by(name)", "Accept-Profile", "tenant_a")
                .expect(status().isBadRequest());
    }

    @Test
    void rest_aliasEqualToASelectedColumn_is400_notOneOverwritingTheOther() {
        http.get(REST + "/docs?select=id,id:users!created_by(name)", "Accept-Profile", "tenant_a")
                .expect(status().isBadRequest())
                .expect(jsonPath("$.code", is("embed_key_collision")));
        http.get(REST + "/docs?select=*,created_by:users!created_by(name)", "Accept-Profile", "tenant_a")
                .expect(status().isBadRequest())
                .expect(jsonPath("$.code", is("embed_key_collision")));
    }

    @Test
    void rest_anAliasedEmbedBesideASchemaQualifiedOne_keepsBothKeys() {
        http.get(REST + "/audit?select=id,creator:users!a_user(name),tenant_b.users!b_user(name)",
                        "Accept-Profile", "tenant_a")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].creator.name", is("from-a")))
                .expect(jsonPath("$.data[0].users.name", is("from-b")));
    }

    @Test
    void rest_anAliasedEmbed_comesBackAfterTheColumns() {
        org.assertj.core.api.Assertions.assertThat(
                        http.get(REST + "/docs?select=creator:users!created_by(name),id", "Accept-Profile", "tenant_a")
                                .expect(status().isOk()).body())
                .isEqualTo("{\"data\":[{\"id\":1,\"creator\":{\"name\":\"from-a\"}}]}");
    }

    @Test
    void rest_aliasEqualToAColumnOfANestedForwardEmbed_is400_namingTheColumn() {
        http.get(REST + "/badges?select=id,profiles(id,id:users(name))", "Accept-Profile", "tenant_a")
                .expect(status().isBadRequest())
                .expect(jsonPath("$.code", is("embed_key_collision")))
                .expect(jsonPath("$.message", containsString("collides with column 'id'")));
    }

    @Test
    void rest_aliasEqualToAColumnOfANestedReverseEmbed_is400_namingTheColumn() {
        http.get(REST + "/users?select=id,profiles(id,id:badges(id))", "Accept-Profile", "tenant_a")
                .expect(status().isBadRequest())
                .expect(jsonPath("$.code", is("embed_key_collision")))
                .expect(jsonPath("$.message", containsString("collides with column 'id'")));
    }

    @Test
    void rest_aliasEqualToATopLevelColumn_namesTheColumn_notAMissingAlias() {
        http.get(REST + "/docs?select=id,id:users!created_by(name)", "Accept-Profile", "tenant_a")
                .expect(status().isBadRequest())
                .expect(jsonPath("$.message", containsString("collides with column 'id'")));
    }
}
