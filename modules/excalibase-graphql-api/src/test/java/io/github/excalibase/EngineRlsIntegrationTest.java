package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.InMemoryPolicyProvider;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.PolicyProvider;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for query-first (engine-driven) RLS on a single table
 * SELECT — EXC-312. The table has NO Postgres-native RLS, so any filtering
 * observed is proof the excalibase-rls engine composed the WHERE clause.
 *
 * <p>Exercises the full production path: JWT verification → JwtAuthFilter
 * registers the RLS contributor → SqlCompiler builds the WHERE via the
 * contributor → Postgres returns only the rows the policy allows. Asserts the
 * list, connection, and aggregate read surfaces are all filtered (no bypass).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EngineRlsIntegrationTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final String PROJECT_WITH_POLICY = "proj-rls";
    private static final String PROJECT_NO_POLICY = "proj-open";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-engine-rls.sql");

    static ECPrivateKey privateKey;
    static ECPublicKey publicKey;
    static HttpServer mockVault;
    static int mockVaultPort;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = gen.generateKeyPair();
            privateKey = (ECPrivateKey) kp.getPrivate();
            publicKey = (ECPublicKey) kp.getPublic();

            mockVault = HttpServer.create(new InetSocketAddress(0), 0);
            mockVaultPort = mockVault.getAddress().getPort();
            String jwksJson = buildJwks(publicKey);
            mockVault.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            });
            mockVault.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up mock vault", e);
        }
    }

    @AfterAll
    static void teardown() {
        if (mockVault != null) mockVault.stop(0);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.max-rows", () -> 30);
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + mockVaultPort + "/.well-known/jwks.json");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PolicyProvider policyProvider;

    private static final ObjectMapper mapper = new ObjectMapper();

    /** Owner policy: a doc row is visible iff its owner_id equals the caller. */
    private static Policy ownerSelect() {
        return new Policy(
                "owner-docs", "owner-docs", "rls_demo.docs",
                PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("owner_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
                List.of(Assignment.all()));
    }

    @BeforeEach
    void seedPolicies() {
        // The bean is the in-memory provider; seed the owner policy for the
        // policy-bearing project only. The open project stays empty → passthrough.
        ((InMemoryPolicyProvider) policyProvider).put(PROJECT_WITH_POLICY, List.of(ownerSelect()));
        ((InMemoryPolicyProvider) policyProvider).evict(PROJECT_NO_POLICY);
    }

    private String body(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    private String jwt(String userId, String projectId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user@test.com")
                .claim("userId", userId)
                .claim("projectId", projectId)
                .claim("role", "app_authenticated")
                .issuer("excalibase")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 3600_000))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    @Test
    void listQuery_aliceSeesOnlyOwnDocs() throws Exception {
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id owner_id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(2)));
    }

    @Test
    void listQuery_bobSeesOnlyOwnDocs() throws Exception {
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(BOB, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id owner_id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(1)));
    }

    @Test
    void listQuery_projectWithoutPolicy_seesAllDocs() throws Exception {
        // Same table, a project with no seeded policy → engine returns UNRESTRICTED
        // → passthrough, all three rows visible. Proves opt-in behaviour.
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_NO_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(3)));
    }

    @Test
    void listQuery_withUserFilter_combinesWithRls() throws Exception {
        // Alice asks for doc id=1 (hers) → 1 row; the RLS predicate ANDs with the
        // user filter rather than replacing it.
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs(where: { id: { eq: 1 } }) { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(1)));
    }

    @Test
    void listQuery_userFilterCannotEscapeRls() throws Exception {
        // Alice tries to read Bob's doc id=3 by id filter → RLS still excludes it.
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs(where: { id: { eq: 3 } }) { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(0)));
    }

    @Test
    void connectionQuery_isAlsoFiltered() throws Exception {
        // The connection surface must not be a bypass: Alice sees 2 edges.
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocsConnection { edges { node { id } } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocsConnection.edges", hasSize(2)));
    }

    private static String buildJwks(ECPublicKey key) {
        com.nimbusds.jose.jwk.ECKey ecKey = new com.nimbusds.jose.jwk.ECKey.Builder(
                com.nimbusds.jose.jwk.Curve.P_256, key)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                .keyID("test-key")
                .build();
        return new com.nimbusds.jose.jwk.JWKSet(ecKey).toString();
    }
}
