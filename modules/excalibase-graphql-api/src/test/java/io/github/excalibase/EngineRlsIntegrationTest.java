package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.ColumnPolicy;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.InMemoryPolicyProvider;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.MaskMode;
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
    private static final String PROJECT_CLS = "proj-cls";
    private static final String PROJECT_CLS_NULL = "proj-cls-null";
    private static final String PROJECT_NESTED = "proj-nested";
    private static final String PROJECT_NUMERIC = "proj-numeric";
    private static final String PROJECT_TEMPORAL = "proj-temporal";

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

    /** Column policy: HIDE the `title` column for everyone. */
    private static ColumnPolicy hideTitle() {
        return new ColumnPolicy(
                "hide-title", "hide-title", "rls_demo.docs",
                java.util.Set.of("title"), Operation.ALL, MaskMode.HIDE,
                null, null, 0, true, List.of(Assignment.all()));
    }

    @BeforeEach
    void seedPolicies() {
        var provider = (InMemoryPolicyProvider) policyProvider;
        // Row-policy project: owner filter, no column masking.
        provider.put(PROJECT_WITH_POLICY, List.of(ownerSelect()));
        provider.putColumns(PROJECT_WITH_POLICY, List.of());
        // Open project: nothing seeded → full passthrough.
        provider.evict(PROJECT_NO_POLICY);
        // CLS project: no row policy (all rows visible) but `title` is hidden,
        // isolating column-level security from row-level filtering.
        provider.put(PROJECT_CLS, List.of());
        provider.putColumns(PROJECT_CLS, List.of(hideTitle()));
        // CLS-NULL project: `title` is null-masked (key present, value null).
        provider.put(PROJECT_CLS_NULL, List.of());
        provider.putColumns(PROJECT_CLS_NULL, List.of(new ColumnPolicy(
                "null-title", "null-title", "rls_demo.docs",
                java.util.Set.of("title"), Operation.ALL, MaskMode.NULL,
                null, null, 0, true, List.of(Assignment.all()))));
        // Nested project: owner policy on `book` only (not `shelf`), so the
        // embedded books under a shelf must be filtered per caller.
        provider.put(PROJECT_NESTED, List.of(new Policy(
                "owner-book", "owner-book", "rls_demo.book",
                PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("owner_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
                List.of(Assignment.all()))));
        // Numeric project: DECIMAL rule binds as BigDecimal → exact NUMERIC compare.
        provider.put(PROJECT_NUMERIC, List.of(new Policy(
                "min-amount", "min-amount", "rls_demo.ledger",
                PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("amount", FieldType.DECIMAL, RuleOperator.GTE, "100.00")),
                List.of(Assignment.all()))));
        // Temporal project: DATETIME rule binds as OffsetDateTime → TIMESTAMPTZ compare.
        provider.put(PROJECT_TEMPORAL, List.of(new Policy(
                "recent", "recent", "rls_demo.ledger",
                PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("created_at", FieldType.DATETIME, RuleOperator.GTE, "{{daysAgo:1}}")),
                List.of(Assignment.all()))));
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

    @Test
    void cls_hiddenColumnIsDroppedFromResponse() throws Exception {
        // No row policy on PROJECT_CLS → all 3 rows; `title` HIDE → key absent.
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_CLS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(3)))
                .andExpect(jsonPath("$.data.rlsDemoDocs[0].id").exists())
                .andExpect(jsonPath("$.data.rlsDemoDocs[0].title").doesNotExist());
    }

    @Test
    void cls_nonHiddenColumnsStillReturned() throws Exception {
        // Hiding `title` must not affect other columns.
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_CLS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id owner_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs[0].id").exists())
                .andExpect(jsonPath("$.data.rlsDemoDocs[0].owner_id").exists());
    }

    @Test
    void cls_projectWithoutColumnPolicy_titleVisible() throws Exception {
        // The row-policy project has no column policy → title is present.
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs[0].title").exists());
    }

    @Test
    void cls_nullMaskedColumnHasNoRealValue() throws Exception {
        // NULL mask emits `'title', NULL` (engine selectList "NULL AS title"). Postgres
        // keeps the null, but the GraphQL response serializer strips null fields, so the
        // key is omitted at the boundary — the real value never leaves the database
        // (the security property), even though the cosmetic key differs from HIDE.
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_CLS_NULL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(3)))
                .andExpect(jsonPath("$.data.rlsDemoDocs[0].id").exists())
                .andExpect(jsonPath("$.data.rlsDemoDocs[0].title").doesNotExist());
    }




    @Test
    void nested_embeddedRelationIsFiltered_alice() throws Exception {
        // One shelf, three books (2 Alice, 1 Bob). The book policy filters the
        // embedded collection: Alice sees her 2 books nested under the shelf.
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_NESTED))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoShelf { id rlsDemoBook { id title } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoShelf", hasSize(1)))
                .andExpect(jsonPath("$.data.rlsDemoShelf[0].rlsDemoBook", hasSize(2)));
    }

    @Test
    void nested_embeddedRelationIsFiltered_bob() throws Exception {
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(BOB, PROJECT_NESTED))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoShelf { id rlsDemoBook { id title } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoShelf[0].rlsDemoBook", hasSize(1)));
    }



    @Test
    void typePrecise_decimalThresholdFiltersExactly() throws Exception {
        // amount >= 100.00 → rows 1 (100.00) and 2 (250.50); excludes 3 (99.99).
        // A lossy double bind would risk boundary errors; BigDecimal is exact.
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_NUMERIC))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoLedger { id amount } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoLedger", hasSize(2)));
    }

    @Test
    void typePrecise_timestamptzFilters() throws Exception {
        // created_at >= (now - 1 day) → rows 2 and 3 (created now); excludes row 1
        // (10 days old). Proves DATETIME binds as a real timestamptz operand.
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_TEMPORAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoLedger { id created_at } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoLedger", hasSize(2)));
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
