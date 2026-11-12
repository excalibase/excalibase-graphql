package io.github.excalibase;

import io.github.excalibase.support.DatabaseAccess;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static io.github.excalibase.PermissionDocs.entry;
import static io.github.excalibase.PermissionDocs.insert;
import static io.github.excalibase.PermissionDocs.select;
import static io.github.excalibase.PermissionDocs.selectAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * Aliases at every depth, relationship names and arguments, and bigint keys that round-trip as the
 * strings queries return them as, over HTTP on real Postgres with the engine enforcing permissions.
 */
class GraphqlRelationshipsIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-relationships";
    private static final String ANON = "anon";
    private static final String PERMISSIONS = PermissionDocs.document(PROJECT,
            entry("public.teams", ANON, selectAll()),
            entry("public.matches", ANON, select("{}", "\"*\"", null, true)),
            entry("public.goals", ANON, selectAll(),
                    insert("{\"minute\":{\"_gte\":0}}", "[\"match_id\",\"minute\",\"scorer\"]", "{}")));

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
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

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.datasource(postgres);
        properties.put("app.database-type", "postgres");
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.security.auth.jwks-url", "http://localhost:" + controlPlane.getAddress().getPort() + "/.well-known/jwks.json");
        properties.put("app.project-id", PROJECT);
        properties.put("app.security.rls.policy-url", "http://localhost:" + controlPlane.getAddress().getPort() + "/api");
        properties.put("app.security.rls.policy-pat", "test-pat");
        properties.put("app.security.rls.policy-ttl-ms", "0");
        return properties;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();


    private JdbcTemplate jdbc;

    @BeforeEach
    void openJdbc() {
        jdbc = DatabaseAccess.template(postgres);
    }

    private HttpAnswer graphql(String query) throws Exception {
        return http.post("/" + PROJECT + "/graphql", MAPPER.writeValueAsString(Map.of("query", query)), "Content-Type", "application/json");
    }

    private HttpAnswer restInsert(String table, String body) throws Exception {
        return http.post("/" + PROJECT + "/api/v1/" + table, body, "Content-Type", "application/json", "Accept-Profile", "public", "Content-Profile", "public", "Prefer", "return=representation");
    }

    // ---- aliases ----

    @Test
    void aliasesInsideASelection_nameTheirKeys() throws Exception {
        graphql("{ publicMatches(where: { id: { eq: \"1\" } }) { matchId: id "
                + "home: publicHomeTeam { teamName: name } "
                + "scored: publicGoals { at: minute } } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicMatches[0].matchId").value("1"))
                .expect(jsonPath("$.data.publicMatches[0].id").doesNotExist())
                .expect(jsonPath("$.data.publicMatches[0].home.teamName").value("Lions"))
                .expect(jsonPath("$.data.publicMatches[0].home.name").doesNotExist())
                .expect(jsonPath("$.data.publicMatches[0].scored", hasSize(3)))
                .expect(jsonPath("$.data.publicMatches[0].scored[0].at").exists());
    }

    @Test
    void oneFieldUnderTwoAliases_answersBoth() throws Exception {
        graphql("{ publicTeams(where: { id: { eq: \"1\" } }) { a: name b: name } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicTeams[0].a").value("Lions"))
                .expect(jsonPath("$.data.publicTeams[0].b").value("Lions"));
    }

    @Test
    void aliasesInsideAConnectionAndAnAggregate_nameTheirKeys() throws Exception {
        graphql("{ publicMatchesConnection(first: 1) { rows: edges { at: cursor row: node { key: id } } "
                + "info: pageInfo { more: hasNextPage } total: totalCount } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicMatchesConnection.rows[0].row.key").value("1"))
                .expect(jsonPath("$.data.publicMatchesConnection.rows[0].at").exists())
                .expect(jsonPath("$.data.publicMatchesConnection.info.more").value(true))
                .expect(jsonPath("$.data.publicMatchesConnection.total").value(3));
        graphql("{ publicMatchesAggregate { n: count top: max { team: home_team_id } } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicMatchesAggregate.n").value(3))
                .expect(jsonPath("$.data.publicMatchesAggregate.top.team").value(2));
    }

    @Test
    void aliasesInAMutationResult_nameTheirKeys() throws Exception {
        graphql("mutation { createPublicGoals(input: { match_id: \"2\", minute: 3, scorer: \"Ed\" }) "
                + "{ goal: id when: minute match: publicMatch { opponent: publicAwayTeam { name } } } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createPublicGoals.goal").exists())
                .expect(jsonPath("$.data.createPublicGoals.when").value(3))
                .expect(jsonPath("$.data.createPublicGoals.match.opponent.name").value("Bears"));
    }

    // ---- relationship names ----

    @Test
    void twoKeysToTheSameTable_areNamedForTheirColumnsWithoutTheId() throws Exception {
        graphql("{ publicMatches(where: { id: { eq: \"3\" } }) { publicHomeTeam { name } publicAwayTeam { name } "
                + "publicWinnerTeam { name } } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicMatches[0].publicHomeTeam.name").value("Tigers"))
                .expect(jsonPath("$.data.publicMatches[0].publicAwayTeam.name").value("Lions"))
                .expect(jsonPath("$.data.publicMatches[0].publicWinnerTeam.name").value("Tigers"));
        graphql("{ publicTeams(where: { id: { eq: \"1\" } }) { publicMatchesByHomeTeam { id } "
                + "publicMatchesByAwayTeam { id } publicMatchesByWinnerTeam { id } } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicTeams[0].publicMatchesByHomeTeam[*].id", contains("1", "2")))
                .expect(jsonPath("$.data.publicTeams[0].publicMatchesByAwayTeam[*].id", contains("3")))
                .expect(jsonPath("$.data.publicTeams[0].publicMatchesByWinnerTeam[*].id", contains("1")));
    }

    @Test
    void theOldIdLikeNames_stillAnswer() throws Exception {
        graphql("{ publicMatches(where: { id: { eq: \"3\" } }) { publicHomeTeamId { name } publicAwayTeamId { name } } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicMatches[0].publicHomeTeamId.name").value("Tigers"))
                .expect(jsonPath("$.data.publicMatches[0].publicAwayTeamId.name").value("Lions"));
        graphql("{ publicTeams(where: { id: { eq: \"1\" } }) { publicMatchesHomeTeamId { id } } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicTeams[0].publicMatchesHomeTeamId[*].id", contains("1", "2")));
    }

    @Test
    void introspection_marksTheOldNamesDeprecated() throws Exception {
        graphql("{ __type(name: \"PublicMatches\") { fields(includeDeprecated: true) { name isDeprecated deprecationReason } } }")
                .expect(jsonPath("$.data.__type.fields[?(@.name == 'publicHomeTeam')].isDeprecated", contains(false)))
                .expect(jsonPath("$.data.__type.fields[?(@.name == 'publicHomeTeamId')].isDeprecated", contains(true)))
                .expect(jsonPath("$.data.__type.fields[?(@.name == 'publicHomeTeamId')].deprecationReason",
                        contains("Use publicHomeTeam")))
                .expect(jsonPath("$.data.__type.fields[?(@.name == 'publicGoals')].isDeprecated", contains(false)));
        graphql("{ __type(name: \"PublicTeams\") { fields(includeDeprecated: true) { name isDeprecated } } }")
                .expect(jsonPath("$.data.__type.fields[?(@.name == 'publicMatchesByHomeTeam')].isDeprecated",
                        contains(false)))
                .expect(jsonPath("$.data.__type.fields[?(@.name == 'publicMatchesHomeTeamId')].isDeprecated",
                        contains(true)));
        graphql("{ __type(name: \"PublicTeams\") { fields { name } } }")
                .expect(jsonPath("$.data.__type.fields[*].name", not(hasItem("publicMatchesHomeTeamId"))));
    }

    @Test
    void oneSelection_mayNameARelationshipByEitherName() throws Exception {
        // Permission rules under either name: PermissionRelationshipNamesIntegrationTest.
        graphql("{ publicMatches(where: { id: { eq: \"1\" } }) { publicHomeTeam { name } publicHomeTeamId { name } } }")
                .expect(jsonPath("$.data.publicMatches[0].publicHomeTeam.name").value("Lions"))
                .expect(jsonPath("$.data.publicMatches[0].publicHomeTeamId.name").value("Lions"));
    }

    // ---- relationship arguments ----

    @Test
    void aReverseRelationship_takesWhereOrderByLimitAndOffset() throws Exception {
        graphql("{ publicMatches(where: { id: { eq: \"1\" } }) { "
                + "late: publicGoals(where: { minute: { gt: 30 } }, orderBy: { minute: DESC }) { minute } "
                + "first: publicGoals(orderBy: { minute: ASC }, limit: 1) { minute } "
                + "second: publicGoals(orderBy: { minute: ASC }, limit: 1, offset: 1) { minute } } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.publicMatches[0].late[*].minute", contains(80, 55)))
                .expect(jsonPath("$.data.publicMatches[0].first[*].minute", contains(10)))
                .expect(jsonPath("$.data.publicMatches[0].second[*].minute", contains(55)));
    }

    @Test
    void aReverseRelationship_refusesAnUnknownOrderColumn() throws Exception {
        graphql("{ publicMatches { publicGoals(orderBy: { nope: ASC }) { minute } } }")
                .expect(jsonPath("$.errors[0].message").exists())
                .expect(jsonPath("$.data.publicMatches").doesNotExist());
    }

    @Test
    void introspection_declaresTheListArguments() throws Exception {
        graphql("{ __type(name: \"PublicMatches\") { fields { name args { name type { name } } } } }")
                .expect(jsonPath("$.data.__type.fields[?(@.name == 'publicGoals')].args[*].name",
                        contains("where", "orderBy", "limit", "offset")))
                .expect(jsonPath("$.data.__type.fields[?(@.name == 'publicGoals')].args[?(@.name == 'orderBy')].type.name",
                        contains("PublicGoalsOrderByInput")));
        graphql("{ __schema { queryType { fields { name args { name } } } } }")
                .expect(jsonPath("$.data.__schema.queryType.fields[?(@.name == 'publicGoals')].args[*].name",
                        contains("where", "orderBy", "limit", "offset")));
    }

    // ---- bigint keys ----

    @Test
    void aBigintKeySentAsTheStringQueriesReturn_isWrittenOverRest() throws Exception {
        restInsert("goals", "{\"match_id\":\"3\",\"minute\":5,\"scorer\":\"rest-string-key\"}")
                .expect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT match_id FROM goals WHERE scorer = 'rest-string-key'", Long.class))
                .isEqualTo(3L);
    }

    @Test
    void aBigintKeySentAsTheStringQueriesReturn_isWrittenOverGraphql() throws Exception {
        graphql("mutation { createPublicGoals(input: { match_id: \"3\", minute: 6, scorer: \"gql-string-key\" }) { id } }")
                .expect(jsonPath("$.errors").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT match_id FROM goals WHERE scorer = 'gql-string-key'", Long.class))
                .isEqualTo(3L);
    }

    @Test
    void aDatabaseRefusalOfAWriteWithACheck_isNotReportedAsAFailedCheck() throws Exception {
        String body = restInsert("goals", "{\"match_id\":\"999\",\"minute\":5,\"scorer\":\"no-such-match\"}")
                .body();
        assertThat(body).doesNotContain("permission_check_failed");
        graphql("mutation { createPublicGoals(input: { match_id: \"999\", minute: 5 }) { id } }")
                .expect(jsonPath("$.errors[0].extensions.code", not("permission_check_failed")));
    }

    @Test
    void aFailedCheck_isStillReported() throws Exception {
        restInsert("goals", "{\"match_id\":\"1\",\"minute\":-1}")
                .expect(status().isForbidden());
    }
}
