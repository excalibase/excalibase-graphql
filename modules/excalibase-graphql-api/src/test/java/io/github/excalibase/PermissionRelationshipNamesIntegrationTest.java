package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.excalibase.PermissionDocs.entry;
import static io.github.excalibase.PermissionDocs.insert;
import static io.github.excalibase.PermissionDocs.select;
import static io.github.excalibase.PermissionDocs.selectAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * A permission written with a relationship's id-like name ({@code publicHomeTeamId}) keeps enforcing now
 * that the relationship is also called {@code publicHomeTeam}, and a permission written with the new name
 * enforces the same: rows are allowed and denied identically under both names.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PermissionRelationshipNamesIntegrationTest {

    private static final String PROJECT = "proj-permission-relationship-names";
    private static final String ANON = "anon";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-relationships.sql");

    private static final AtomicReference<String> PERMISSIONS = new AtomicReference<>();
    private static final AtomicLong VERSION = new AtomicLong();
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshGoals() {
        jdbc.update("DELETE FROM goals WHERE id >= 100");
    }

    private static void permit(String... entries) {
        PERMISSIONS.set(PermissionDocs.document(PROJECT, VERSION.incrementAndGet(), entries));
    }

    private ResultActions graphql(String query) throws Exception {
        return mockMvc.perform(post("/" + PROJECT + "/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("query", query))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"publicHomeTeamId", "publicHomeTeam"})
    void aSelectFilterThroughAnObjectRelationship_enforcesUnderEitherName(String relationship) throws Exception {
        permit(entry("public.teams", ANON, selectAll()),
                entry("public.matches", ANON,
                        select("{\"" + relationship + "\":{\"name\":{\"_eq\":\"Lions\"}}}", "\"*\"")));

        // Lions are home in matches 1 and 2; match 3 is the Tigers' home game and is denied.
        graphql("{ publicMatches { id } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicMatches[*].id", containsInAnyOrder("1", "2")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"publicMatchesWinnerTeamId", "publicMatchesByWinnerTeam"})
    void aSelectFilterThroughAnArrayRelationship_enforcesUnderEitherName(String relationship) throws Exception {
        permit(entry("public.matches", ANON, selectAll()),
                entry("public.teams", ANON, select("{\"" + relationship + "\":{}}", "\"*\"")));

        // Lions won match 1 and Tigers match 3; the Bears never won and are denied.
        graphql("{ publicTeams { name } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicTeams[*].name", containsInAnyOrder("Lions", "Tigers")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"publicMatchId", "publicMatch"})
    void anInsertCheckThroughARelationship_enforcesUnderEitherName(String relationship) throws Exception {
        permit(entry("public.matches", ANON, selectAll()),
                entry("public.goals", ANON, selectAll(),
                        insert("{\"" + relationship + "\":{\"home_team_id\":{\"_eq\":1}}}",
                                "[\"match_id\",\"minute\",\"scorer\"]", "{}")));

        // Match 1 is a Lions home game: allowed.
        graphql("mutation { createPublicGoals(input: { match_id: 1, minute: 5, scorer: \"Ed\" }) { scorer } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicGoals.scorer").value("Ed"));
        // Match 3 is a Tigers home game: the check denies it and nothing is written.
        graphql("mutation { createPublicGoals(input: { match_id: 3, minute: 6, scorer: \"Flo\" }) { scorer } }")
                .andExpect(jsonPath("$.errors").exists());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goals WHERE scorer = 'Flo'", Integer.class)).isZero();
    }
}
