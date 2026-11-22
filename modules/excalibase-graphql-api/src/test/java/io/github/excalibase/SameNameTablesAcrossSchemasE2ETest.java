package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
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
}
