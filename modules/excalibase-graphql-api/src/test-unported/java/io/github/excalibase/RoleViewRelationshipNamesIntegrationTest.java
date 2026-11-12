package io.github.excalibase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.excalibase.PermissionDocs.entry;
import static io.github.excalibase.PermissionDocs.select;
import static io.github.excalibase.PermissionDocs.selectAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * A relationship has one name whatever role asks: a role's schema names every relationship it can
 * see exactly as the whole schema does, also when it sees only some of several keys between two tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RoleViewRelationshipNamesIntegrationTest {

    private static final String PROJECT = "proj-role-view-relationship-names";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> SERVICE = Map.of("role", "service", "scope", "service");
    private static final Map<String, Object> FAN = Map.of("role", "fan");
    private static final String[] TYPES = {"PublicTeams", "PublicSeasons", "PublicMatches", "PublicPlayers",
            "PublicTransfers"};

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-relationship-order.sql");

    static ECPrivateKey privateKey;
    static HttpServer controlPlane;
    private static final AtomicReference<String> PERMISSIONS = new AtomicReference<>();
    private static final AtomicLong VERSION = new AtomicLong();

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            privateKey = (ECPrivateKey) keyPair.getPrivate();
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
            PermissionDocs.serve(controlPlane, PROJECT, PERMISSIONS::get);
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

    @Autowired
    private MockMvc mockMvc;

    /** fan reads everything; anon reads only the home team of a match and the club a player left. */
    @BeforeEach
    void permit() {
        String everyColumn = "\"*\"";
        PERMISSIONS.set(PermissionDocs.document(PROJECT, VERSION.incrementAndGet(),
                entry("public.teams", "fan", selectAll()),
                entry("public.seasons", "fan", selectAll()),
                entry("public.matches", "fan", selectAll()),
                entry("public.players", "fan", selectAll()),
                entry("public.transfers", "fan", selectAll()),
                entry("public.teams", "anon", select("{}", everyColumn)),
                entry("public.seasons", "anon", select("{}", everyColumn)),
                entry("public.matches", "anon",
                        select("{}", "[\"id\",\"home_team_id\",\"league\",\"year\",\"replay_of_season_id\"]")),
                entry("public.players", "anon", select("{}", everyColumn)),
                entry("public.transfers", "anon", select("{}", "[\"id\",\"player_id\",\"from_team_id\"]"))));
    }

    @Test
    void theWholeSchema_namesEachOfSeveralKeysFromOneTableForItsColumns() throws Exception {
        assertThat(fieldNames(SERVICE, "PublicTeams")).contains(
                        "publicMatchesHomeTeamId", "publicMatchesAwayTeamId", "publicMatchesWinnerTeamId",
                        "publicMatchesByHomeTeam", "publicMatchesByAwayTeam", "publicMatchesByWinnerTeam",
                        "publicTransfersFromTeamId", "publicTransfersToTeamId", "publicPlayers")
                .doesNotContain("publicMatches", "publicTransfers");
        assertThat(fieldNames(SERVICE, "PublicSeasons"))
                .contains("publicMatchesLeagueYear", "publicMatchesReplayOfSeasonId", "publicMatchesByReplayOfSeason")
                .doesNotContain("publicMatches");
    }

    @Test
    void aRoleThatSeesEverything_namesEveryRelationshipAsTheWholeSchemaDoes() throws Exception {
        for (String type : TYPES) {
            assertThat(fieldNames(FAN, type)).as(type).isEqualTo(fieldNames(SERVICE, type));
        }
    }

    @Test
    void aRoleThatSeesSomeKeys_keepsTheWholeSchemasNameForEachOneItSees() throws Exception {
        Set<String> expectedTeams = new TreeSet<>(fieldNames(SERVICE, "PublicTeams"));
        expectedTeams.removeAll(Set.of("publicMatchesAwayTeamId", "publicMatchesByAwayTeam",
                "publicMatchesWinnerTeamId", "publicMatchesByWinnerTeam",
                "publicTransfersToTeamId", "publicTransfersByToTeam"));
        assertThat(fieldNames(null, "PublicTeams")).isEqualTo(expectedTeams);
        assertThat(fieldNames(null, "PublicSeasons")).isEqualTo(fieldNames(SERVICE, "PublicSeasons"));
        assertThat(fieldNames(null, "PublicPlayers")).isEqualTo(fieldNames(SERVICE, "PublicPlayers"));
        Set<String> expectedMatches = new TreeSet<>(fieldNames(SERVICE, "PublicMatches"));
        expectedMatches.removeAll(Set.of("away_team_id", "publicAwayTeamId", "publicAwayTeam",
                "winner_team_id", "publicWinnerTeamId", "publicWinnerTeam"));
        assertThat(fieldNames(null, "PublicMatches")).isEqualTo(expectedMatches);
        Set<String> expectedTransfers = new TreeSet<>(fieldNames(SERVICE, "PublicTransfers"));
        expectedTransfers.removeAll(Set.of("to_team_id", "publicToTeamId", "publicToTeam"));
        assertThat(fieldNames(null, "PublicTransfers")).isEqualTo(expectedTransfers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"publicMatchesHomeTeamId", "publicMatchesByHomeTeam"})
    void aRoleThatSeesOneOfSeveralKeys_answersToBothOfItsNames(String relationship) throws Exception {
        graphql(null, "{ publicTeams(where: { id: { eq: \"1\" } }) { " + relationship + " { id } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicTeams[0]." + relationship + "[*].id", contains("1")));
    }

    private Set<String> fieldNames(Map<String, Object> claims, String type) throws Exception {
        String body = graphql(claims, "{ __type(name: \"" + type + "\") { fields(includeDeprecated: true) { name } } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Set<String> names = new TreeSet<>();
        for (JsonNode field : MAPPER.readTree(body).path("data").path("__type").path("fields")) {
            // Relationship aggregates follow the role's allowAggregations, not its naming; compared elsewhere.
            if (!field.path("name").asText().endsWith("Aggregate")) {
                names.add(field.path("name").asText());
            }
        }
        assertThat(names).as(type + " is served").isNotEmpty();
        return names;
    }

    private ResultActions graphql(Map<String, Object> claims, String query) throws Exception {
        MockHttpServletRequestBuilder request = post("/" + PROJECT + "/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("query", query)));
        if (claims != null) {
            request.header("Authorization", "Bearer " + token(claims));
        }
        return mockMvc.perform(request);
    }

    private static String token(Map<String, Object> claims) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject("fan@test.com")
                .claim("userId", "u-1")
                .claim("projectId", PROJECT)
                .audience("excalibase:" + PROJECT)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)));
        claims.forEach(builder::claim);
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), builder.build());
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }
}
