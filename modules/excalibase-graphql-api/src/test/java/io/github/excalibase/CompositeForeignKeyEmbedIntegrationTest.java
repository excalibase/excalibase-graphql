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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
    void rest_keysSharingTheirFirstColumn_suggestHintsThatTellThemApart_andEachResolves() {
        http.get(REST + "/tchild?select=id,tparent(name)")
                .expect(status().is(300))
                .expect(jsonPath("$.message", containsString("tparent!a_id")))
                .expect(jsonPath("$.message", containsString("tparent!b_id")))
                .expect(jsonPath("$.message", not(containsString("tparent!tenant"))));
        http.get(REST + "/tchild?select=id,tparent!a_id(name)&order=id.asc")
                .expect(status().isOk())
                .expect(jsonPath("$.data[*].tparent.name", contains("p1", "p2")));
        http.get(REST + "/tchild?select=id,tparent!b_id(name)&order=id.asc")
                .expect(status().isOk())
                .expect(jsonPath("$.data[*].tparent.name", contains("p2", "p1")));
    }

    @Test
    void rest_reverseKeysSharingTheirFirstColumn_suggestHintsThatTellThemApart_andEachResolves() {
        http.get(REST + "/tparent?select=tenant,id,tchild(id)&tenant=eq.1&id=eq.1")
                .expect(status().is(300))
                .expect(jsonPath("$.message", containsString("tchild!a_id")))
                .expect(jsonPath("$.message", containsString("tchild!b_id")))
                .expect(jsonPath("$.message", not(containsString("tchild!tenant"))));
        http.get(REST + "/tparent?select=id,tchild!a_id(id)&tenant=eq.1&id=eq.1")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].tchild[*].id", contains(1)));
        http.get(REST + "/tparent?select=id,tchild!b_id(id)&tenant=eq.1&id=eq.1")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].tchild[*].id", contains(2)));
    }

    @Test
    void rest_reverseHintMayNameAnyColumnOfTheKey_notOnlyTheFirst() {
        http.get(REST + "/parent?select=a,b,transfer!to_b(id)&a=eq.2&b=eq.1")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].transfer[*].id", contains(1)));
        http.get(REST + "/parent?select=a,b,transfer!from_b(id)&a=eq.1&b=eq.2")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].transfer[*].id", contains(1)));
    }

    @Test
    void rest_keysOverTheSameColumns_areToldApartByConstraintName() {
        http.get(REST + "/gchild?select=id,gparent(name)")
                .expect(status().is(300))
                .expect(jsonPath("$.message", containsString("gparent!gchild_by_pk")))
                .expect(jsonPath("$.message", containsString("gparent!gchild_by_alt")));
        http.get(REST + "/gchild?select=id,gparent!gchild_by_pk(name)&order=id.asc")
                .expect(status().isOk())
                .expect(jsonPath("$.data[*].gparent.name", contains("one", "two")));
        http.get(REST + "/gchild?select=id,gparent!gchild_by_alt(name)&order=id.asc")
                .expect(status().isOk())
                .expect(jsonPath("$.data[*].gparent.name", contains("two", "one")));
    }

    @Test
    void rest_reverseKeysOverTheSameColumns_areToldApartByConstraintName() {
        http.get(REST + "/gparent?select=a,gchild(id)&a=eq.1")
                .expect(status().is(300))
                .expect(jsonPath("$.message", containsString("gchild!gchild_by_alt")));
        http.get(REST + "/gparent?select=a,gchild!gchild_by_alt(id)&a=eq.1")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].gchild[*].id", contains(2)));
    }

    @Test
    void rest_aConstraintNameHint_alsoPicksASingleCompositeKey() {
        http.get(REST + "/transfer?select=id,parent!transfer_to_a_to_b_fkey(name)")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].parent.name", is("p21")));
    }

    @Test
    void rest_theSameKeyDeclaredTwice_isOneRelationship_notAmbiguous() {
        http.get(REST + "/sparent?select=id,schild(id)")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].schild[*].id", contains(1)));
        http.get(REST + "/parent?select=a,b,dupchild(id)&a=eq.1&b=eq.2")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].dupchild[*].id", contains(1)));
        http.get(REST + "/dupchild?select=id,parent(name)")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].parent.name", is("p12")));
    }

    @Test
    void rest_twoTablesWithTheSameConstraintName_eachKeepTheirOwnKey() {
        http.get(REST + "/fk_a?select=id,ua_parent(label)")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].ua_parent.label", is("from a")));
        http.get(REST + "/fk_b?select=id,ub_parent(label)")
                .expect(status().isOk())
                .expect(jsonPath("$.data[0].ub_parent.label", is("from b")));
    }

    @Test
    void graphql_aDuplicatedKeyKeepsItsPlainFieldName() throws Exception {
        http.post("/test-proj/graphql", mapper.writeValueAsString(Map.of("query",
                        "{ publicSparent { id publicSchild { id } } }")), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.publicSparent[0].publicSchild[0].id", is(1)));
    }

    @Test
    void graphql_compositeKeyRelation_joinsOnEveryColumn() throws Exception {
        http.post("/test-proj/graphql", mapper.writeValueAsString(Map.of("query",
                        "{ publicLine(orderBy: { id: ASC }) { id publicParent { name } } }")), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.publicLine[*].publicParent.name", contains("p12", "p11", "p21")));
    }
}
