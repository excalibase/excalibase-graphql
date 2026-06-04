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
 * Engine-driven RLS on mutations — EXC-314. UPDATE/DELETE get an
 * operation-scoped WHERE filter (you can only mutate rows a policy allows),
 * and INSERT is gated by a RowMatcher WITH-CHECK (you cannot create a row a
 * policy forbids). Uses a dedicated writable table so mutation state never
 * affects the read-path tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EngineRlsMutationIntegrationTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final String PROJECT = "proj-rls";

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
                byte[] b = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, b.length);
                exchange.getResponseBody().write(b);
                exchange.getResponseBody().close();
            });
            mockVault.start();
        } catch (Exception e) {
            throw new RuntimeException("setup failed", e);
        }
    }

    @AfterAll
    static void teardown() {
        if (mockVault != null) mockVault.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
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

    /** Owner policy on the writable notes table, all operations. */
    private static Policy ownerAll() {
        return new Policy("owner-notes", "owner-notes", "rls_demo.notes",
                PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("owner_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
                List.of(Assignment.all()));
    }

    @BeforeEach
    void seed() {
        ((InMemoryPolicyProvider) policyProvider).put(PROJECT, List.of(ownerAll()));
    }

    private String body(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    private String jwt(String userId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u@test.com").claim("userId", userId).claim("projectId", PROJECT)
                .claim("role", "app_authenticated").issuer("excalibase")
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 3600_000))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    private org.springframework.test.web.servlet.ResultActions mutate(String userId, String mutation) throws Exception {
        return mockMvc.perform(post("/graphql")
                .header("Authorization", "Bearer " + jwt(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(mutation)));
    }

    // ---- UPDATE ----

    @Test
    void update_ownRow_succeeds() throws Exception {
        mutate(ALICE, "mutation { updateRlsDemoNotes(where: { id: { eq: 1 } }, "
                + "input: { title: \"alice-edited\" }) { id title } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateRlsDemoNotes", hasSize(1)))
                .andExpect(jsonPath("$.data.updateRlsDemoNotes[0].title").value("alice-edited"));
    }

    @Test
    void update_othersRow_affectsNothing() throws Exception {
        // Alice targets Bob's note (id=2): the UPDATE policy's WHERE excludes it,
        // so zero rows change — RLS, not just a missing filter.
        mutate(ALICE, "mutation { updateRlsDemoNotes(where: { id: { eq: 2 } }, "
                + "input: { title: \"hacked\" }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateRlsDemoNotes", hasSize(0)));
    }

    // ---- DELETE ----

    @Test
    void delete_othersRow_affectsNothing() throws Exception {
        mutate(ALICE, "mutation { deleteRlsDemoNotes(where: { id: { eq: 2 } }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteRlsDemoNotes", hasSize(0)));
    }

    // ---- INSERT WITH-CHECK ----

    @Test
    void insert_ownRow_succeeds() throws Exception {
        mutate(ALICE, "mutation { createRlsDemoNotes(input: { id: 100, "
                + "owner_id: \"" + ALICE + "\", title: \"new\" }) { id owner_id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createRlsDemoNotes.id").value("100"));
    }

    @Test
    void insert_rowForAnotherOwner_isRejected() throws Exception {
        // WITH-CHECK: Alice cannot create a note owned by Bob → policy violation,
        // surfaced as a GraphQL error, and no row is written.
        mutate(ALICE, "mutation { createRlsDemoNotes(input: { id: 101, "
                + "owner_id: \"" + BOB + "\", title: \"sneaky\" }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").exists())
                .andExpect(jsonPath("$.data.createRlsDemoNotes").doesNotExist());
    }

    private static String buildJwks(ECPublicKey key) {
        com.nimbusds.jose.jwk.ECKey ecKey = new com.nimbusds.jose.jwk.ECKey.Builder(
                com.nimbusds.jose.jwk.Curve.P_256, key)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).keyID("test-key").build();
        return new com.nimbusds.jose.jwk.JWKSet(ecKey).toString();
    }
}
