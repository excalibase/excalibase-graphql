package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Map;

import static io.github.excalibase.PermissionDocs.entry;
import static io.github.excalibase.PermissionDocs.insert;
import static io.github.excalibase.PermissionDocs.select;
import static io.github.excalibase.PermissionDocs.selectAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Aliases at every depth, relationship names and arguments, and bigint keys that round-trip as the
 * strings queries return them as, over HTTP on real Postgres with the engine enforcing permissions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class GraphqlRelationshipsIntegrationTest {

    private static final String PROJECT = "proj-relationships";
    private static final String ANON = "anon";
    private static final String PERMISSIONS = PermissionDocs.document(PROJECT,
            entry("public.teams", ANON, selectAll()),
            entry("public.matches", ANON, select("{}", "\"*\"", null, true)),
            entry("public.goals", ANON, selectAll(),
                    insert("{\"minute\":{\"_gte\":0}}", "[\"match_id\",\"minute\",\"scorer\"]", "{}")));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-relationships.sql");

    static HttpServer controlPlane;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            String jwks = new JWKSet(new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                    .keyUse(KeyUse.SIGNATURE).keyID("test-key").build()).toString();
            controlPlane = HttpServer.create(new InetSocketAddress(0), 0);
            controlPlane.createContext("/.well-known/jwks.json", exchange -> {
                byte[] payload = jwks.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            PermissionDocs.serve(controlPlane, PROJECT, () -> PERMISSIONS);
            controlPlane.start();
        } catch (Exception e) {
            throw new IllegalStateException("control plane stub failed to start", e);
        }
    }

    @AfterAll
    static void stopControlPlane() {
        controlPlane.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + controlPlane.getAddress().getPort() + "/.well-known/jwks.json");
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.rls.policy-url",
                () -> "http://localhost:" + controlPlane.getAddress().getPort() + "/api");
        registry.add("app.security.rls.policy-pat", () -> "test-pat");
        registry.add("app.security.rls.policy-ttl-ms", () -> 0);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions graphql(String query) throws Exception {
        return mockMvc.perform(post("/" + PROJECT + "/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("query", query))));
    }

    private ResultActions restInsert(String table, String body) throws Exception {
        return mockMvc.perform(post("/" + PROJECT + "/api/v1/" + table)
                .header("Accept-Profile", "public").header("Content-Profile", "public")
                .header("Prefer", "return=representation")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---- aliases ----

    @Test
    void aliasesInsideASelection_nameTheirKeys() throws Exception {
        graphql("{ publicMatches(where: { id: { eq: \"1\" } }) { matchId: id "
                + "home: publicHomeTeam { teamName: name } "
                + "scored: publicGoals { at: minute } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicMatches[0].matchId").value("1"))
                .andExpect(jsonPath("$.data.publicMatches[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.publicMatches[0].home.teamName").value("Lions"))
                .andExpect(jsonPath("$.data.publicMatches[0].home.name").doesNotExist())
                .andExpect(jsonPath("$.data.publicMatches[0].scored", hasSize(3)))
                .andExpect(jsonPath("$.data.publicMatches[0].scored[0].at").exists());
    }

    @Test
    void oneFieldUnderTwoAliases_answersBoth() throws Exception {
        graphql("{ publicTeams(where: { id: { eq: \"1\" } }) { a: name b: name } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicTeams[0].a").value("Lions"))
                .andExpect(jsonPath("$.data.publicTeams[0].b").value("Lions"));
    }

    @Test
    void aliasesInsideAConnectionAndAnAggregate_nameTheirKeys() throws Exception {
        graphql("{ publicMatchesConnection(first: 1) { rows: edges { at: cursor row: node { key: id } } "
                + "info: pageInfo { more: hasNextPage } total: totalCount } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicMatchesConnection.rows[0].row.key").value("1"))
                .andExpect(jsonPath("$.data.publicMatchesConnection.rows[0].at").exists())
                .andExpect(jsonPath("$.data.publicMatchesConnection.info.more").value(true))
                .andExpect(jsonPath("$.data.publicMatchesConnection.total").value(3));
        graphql("{ publicMatchesAggregate { n: count top: max { team: home_team_id } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicMatchesAggregate.n").value(3))
                .andExpect(jsonPath("$.data.publicMatchesAggregate.top.team").value(2));
    }

    @Test
    void aliasesInAMutationResult_nameTheirKeys() throws Exception {
        graphql("mutation { createPublicGoals(input: { match_id: \"2\", minute: 3, scorer: \"Ed\" }) "
                + "{ goal: id when: minute match: publicMatch { opponent: publicAwayTeam { name } } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicGoals.goal").exists())
                .andExpect(jsonPath("$.data.createPublicGoals.when").value(3))
                .andExpect(jsonPath("$.data.createPublicGoals.match.opponent.name").value("Bears"));
    }

    // ---- relationship names ----

    @Test
    void twoKeysToTheSameTable_areNamedForTheirColumnsWithoutTheId() throws Exception {
        graphql("{ publicMatches(where: { id: { eq: \"3\" } }) { publicHomeTeam { name } publicAwayTeam { name } "
                + "publicWinnerTeam { name } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicMatches[0].publicHomeTeam.name").value("Tigers"))
                .andExpect(jsonPath("$.data.publicMatches[0].publicAwayTeam.name").value("Lions"))
                .andExpect(jsonPath("$.data.publicMatches[0].publicWinnerTeam.name").value("Tigers"));
        graphql("{ publicTeams(where: { id: { eq: \"1\" } }) { publicMatchesByHomeTeam { id } "
                + "publicMatchesByAwayTeam { id } publicMatchesByWinnerTeam { id } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicTeams[0].publicMatchesByHomeTeam[*].id", contains("1", "2")))
                .andExpect(jsonPath("$.data.publicTeams[0].publicMatchesByAwayTeam[*].id", contains("3")))
                .andExpect(jsonPath("$.data.publicTeams[0].publicMatchesByWinnerTeam[*].id", contains("1")));
    }

    @Test
    void theOldIdLikeNames_stillAnswer() throws Exception {
        graphql("{ publicMatches(where: { id: { eq: \"3\" } }) { publicHomeTeamId { name } publicAwayTeamId { name } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicMatches[0].publicHomeTeamId.name").value("Tigers"))
                .andExpect(jsonPath("$.data.publicMatches[0].publicAwayTeamId.name").value("Lions"));
        graphql("{ publicTeams(where: { id: { eq: \"1\" } }) { publicMatchesHomeTeamId { id } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicTeams[0].publicMatchesHomeTeamId[*].id", contains("1", "2")));
    }

    @Test
    void introspection_marksTheOldNamesDeprecated() throws Exception {
        graphql("{ __type(name: \"PublicMatches\") { fields(includeDeprecated: true) { name isDeprecated deprecationReason } } }")
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'publicHomeTeam')].isDeprecated", contains(false)))
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'publicHomeTeamId')].isDeprecated", contains(true)))
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'publicHomeTeamId')].deprecationReason",
                        contains("Use publicHomeTeam")))
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'publicGoals')].isDeprecated", contains(false)));
        graphql("{ __type(name: \"PublicTeams\") { fields(includeDeprecated: true) { name isDeprecated } } }")
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'publicMatchesByHomeTeam')].isDeprecated",
                        contains(false)))
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'publicMatchesHomeTeamId')].isDeprecated",
                        contains(true)));
        graphql("{ __type(name: \"PublicTeams\") { fields { name } } }")
                .andExpect(jsonPath("$.data.__type.fields[*].name", not(hasItem("publicMatchesHomeTeamId"))));
    }

    @Test
    void aPermissionFilter_mayNameARelationshipByEitherName() throws Exception {
        // The same relationship resolves under both names wherever a name is looked up.
        graphql("{ publicMatches(where: { id: { eq: \"1\" } }) { publicHomeTeam { name } publicHomeTeamId { name } } }")
                .andExpect(jsonPath("$.data.publicMatches[0].publicHomeTeam.name").value("Lions"))
                .andExpect(jsonPath("$.data.publicMatches[0].publicHomeTeamId.name").value("Lions"));
    }

    // ---- relationship arguments ----

    @Test
    void aReverseRelationship_takesWhereOrderByLimitAndOffset() throws Exception {
        graphql("{ publicMatches(where: { id: { eq: \"1\" } }) { "
                + "late: publicGoals(where: { minute: { gt: 30 } }, orderBy: { minute: DESC }) { minute } "
                + "first: publicGoals(orderBy: { minute: ASC }, limit: 1) { minute } "
                + "second: publicGoals(orderBy: { minute: ASC }, limit: 1, offset: 1) { minute } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicMatches[0].late[*].minute", contains(80, 55)))
                .andExpect(jsonPath("$.data.publicMatches[0].first[*].minute", contains(10)))
                .andExpect(jsonPath("$.data.publicMatches[0].second[*].minute", contains(55)));
    }

    @Test
    void aReverseRelationship_refusesAnUnknownOrderColumn() throws Exception {
        graphql("{ publicMatches { publicGoals(orderBy: { nope: ASC }) { minute } } }")
                .andExpect(jsonPath("$.errors[0].message").exists())
                .andExpect(jsonPath("$.data.publicMatches").doesNotExist());
    }

    @Test
    void introspection_declaresTheListArguments() throws Exception {
        graphql("{ __type(name: \"PublicMatches\") { fields { name args { name type { name } } } } }")
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'publicGoals')].args[*].name",
                        contains("where", "orderBy", "limit", "offset")))
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'publicGoals')].args[?(@.name == 'orderBy')].type.name",
                        contains("PublicGoalsOrderByInput")));
        graphql("{ __schema { queryType { fields { name args { name } } } } }")
                .andExpect(jsonPath("$.data.__schema.queryType.fields[?(@.name == 'publicGoals')].args[*].name",
                        contains("where", "orderBy", "limit", "offset")));
    }

    // ---- bigint keys ----

    @Test
    void aBigintKeySentAsTheStringQueriesReturn_isWrittenOverRest() throws Exception {
        restInsert("goals", "{\"match_id\":\"3\",\"minute\":5,\"scorer\":\"rest-string-key\"}")
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT match_id FROM goals WHERE scorer = 'rest-string-key'", Long.class))
                .isEqualTo(3L);
    }

    @Test
    void aBigintKeySentAsTheStringQueriesReturn_isWrittenOverGraphql() throws Exception {
        graphql("mutation { createPublicGoals(input: { match_id: \"3\", minute: 6, scorer: \"gql-string-key\" }) { id } }")
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT match_id FROM goals WHERE scorer = 'gql-string-key'", Long.class))
                .isEqualTo(3L);
    }

    @Test
    void aDatabaseRefusalOfAWriteWithACheck_isNotReportedAsAFailedCheck() throws Exception {
        String body = restInsert("goals", "{\"match_id\":\"999\",\"minute\":5,\"scorer\":\"no-such-match\"}")
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("permission_check_failed");
        graphql("mutation { createPublicGoals(input: { match_id: \"999\", minute: 5 }) { id } }")
                .andExpect(jsonPath("$.errors[0].extensions.code", not("permission_check_failed")));
    }

    @Test
    void aFailedCheck_isStillReported() throws Exception {
        restInsert("goals", "{\"match_id\":\"1\",\"minute\":-1}")
                .andExpect(status().isForbidden());
    }
}
