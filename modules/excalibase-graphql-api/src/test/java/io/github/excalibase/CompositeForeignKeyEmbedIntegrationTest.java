package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * A composite foreign key joins on every column pair, never on the first column alone: over REST embeds, forward and
 * reverse, and over GraphQL relations. (EXC-833)
 */
class CompositeForeignKeyEmbedIntegrationTest extends EngineIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-composite-fk.sql");

    private static final String REST = "/test-proj/api/v1";
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.datasource(postgres);
        properties.put("app.project-id", "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        return properties;
    }

    @Test
    void rest_forwardEmbed_joinsOnEveryColumn_soTheRightParentComesBack() {
        http.get(REST + "/line?select=id,parent(name)&order=id.asc")
                .expect(status().isOk())
                .expect(jsonPath("$.data[*].parent.name", contains("p12", "p11", "p21")));
    }

    @Test
    void rest_reverseEmbed_listsOnlyTheChildrenOfThatParent() {
        http.get(REST + "/parent?select=a,b,line(id)&a=eq.1&b=eq.2")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].line", hasSize(1)))
                .expect(jsonPath("$.data[0].line[0].id", is(1)));
    }

    @Test
    void rest_twoCompositeKeysToOneParent_eachHintPicksItsOwn() {
        http.get(REST + "/transfer?select=id,parent!from_a(name)")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].parent.name", is("p12")));
        http.get(REST + "/transfer?select=id,parent!to_a(name)")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].parent.name", is("p21")));
    }

    @Test
    void rest_aHintMayNameAnyColumnOfTheKey_notOnlyTheFirst() {
        http.get(REST + "/transfer?select=id,parent!from_b(name)")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].parent.name", is("p12")));
        http.get(REST + "/transfer?select=id,parent!to_b(name)")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].parent.name", is("p21")));
    }

    @Test
    void rest_twoCompositeKeysToOneParent_withoutAHint_areAmbiguous() {
        http.get(REST + "/transfer?select=id,parent(name)")
                .expect(status().is(300))
                .expect(jsonPath("$.code", is("ambiguous_relationship")));
    }

    @Test
    void graphql_compositeKeyRelation_joinsOnEveryColumn() throws Exception {
        http.post("/test-proj/graphql", mapper.writeValueAsString(Map.of("query",
                        "{ publicLine(orderBy: { id: ASC }) { id publicParent { name } } }")), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.publicLine[*].publicParent.name", contains("p12", "p11", "p21")));
    }
}
