package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for engine-composed select filters and columns. The tables have NO
 * Postgres-native RLS, so any filtering observed is proof the engine composed the WHERE clause from
 * the project's permission document.
 *
 * <p>Exercises the full production path: JWT verification → JwtAuthFilter registers the request's
 * guard from the role's access plan → SqlCompiler ANDs the select filter into every read → Postgres
 * returns only the rows the permission allows.
 *
 * <p>Each permission shape lives in its own project, so this runs multi-tenant: the stub vault
 * resolves every one of those projects to the same test database.
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
    private static final String PROJECT_MFA = "proj-mfa";
    /** Owned rows, readable only after a second factor (aal2). */
    private static final String OWNED_AT_AAL2 = "{\"_and\":[" + PermissionDocs.OWNED
            + ",{\"_exists\":{\"_table\":\"rls_demo.mfa_levels\",\"_where\":{\"level\":{\"_eq\":\"X-Excalibase-Aal\"}}}}]}";
    /** The same rule through a column that names the level each row needs. */
    private static final String OWNED_AT_ROW_LEVEL = "{\"_and\":[" + PermissionDocs.OWNED
            + ",{\"required_aal\":{\"_eq\":\"X-Excalibase-Aal\"}}]}";
    private static final String ROLE = "app_authenticated";
    private static final String ALL = "\"*\"";
    /** Ledger rows created at most a day ago; time variables are not part of the grammar, so a literal. */
    private static final String DAY_AGO = Instant.now().minus(Duration.ofDays(1)).toString();

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
            for (String project : List.of(PROJECT_WITH_POLICY, PROJECT_NO_POLICY, PROJECT_CLS, PROJECT_CLS_NULL,
                    PROJECT_NESTED, PROJECT_NUMERIC, PROJECT_TEMPORAL, PROJECT_MFA)) {
                mockVault.createContext("/api/vault/secrets/projects/" + project + "/credentials/excalibase_app",
                        exchange -> {
                            byte[] body = tenantCredentials().getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body);
                            exchange.getResponseBody().close();
                        });
            }
            permissions().forEach((project, document) -> PermissionDocs.serve(mockVault, project, () -> document));
            mockVault.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up mock vault", e);
        }
    }

    private static String tenantCredentials() {
        return "{\"host\":\"" + postgres.getHost() + "\",\"port\":\"" + postgres.getMappedPort(5432)
                + "\",\"database\":\"" + postgres.getDatabaseName() + "\",\"username\":\""
                + postgres.getUsername() + "\",\"password\":\"" + postgres.getPassword() + "\"}";
    }

    @AfterAll
    static void teardown() {
        if (mockVault != null) mockVault.stop(0);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.max-rows", () -> 30);
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + mockVaultPort + "/.well-known/jwks.json");
        registry.add("app.security.multi-tenant.provisioning-url", () -> "http://localhost:" + mockVaultPort + "/api");
        registry.add("app.tenant-db.sslmode", () -> "disable");
        registry.add("app.security.multi-tenant.provisioning-pat", () -> "test-pat");
        registry.add("app.security.rls.policy-url", () -> "http://localhost:" + mockVaultPort + "/api");
        registry.add("app.security.rls.policy-pat", () -> "test-pat");
    }

    @Autowired
    private MockMvc mockMvc;

    private static final ObjectMapper mapper = new ObjectMapper();

    private static String owned(String table) {
        return PermissionDocs.entry(table, ROLE, PermissionDocs.select(PermissionDocs.OWNED, ALL));
    }

    private static String open(String table) {
        return PermissionDocs.entry(table, ROLE, PermissionDocs.selectAll());
    }

    /** One permission shape per project. */
    private static Map<String, String> permissions() {
        String docsWithoutTitle = PermissionDocs.entry("rls_demo.docs", ROLE,
                PermissionDocs.select("{}", "[\"id\",\"owner_id\"]"));
        return Map.of(
                PROJECT_MFA, PermissionDocs.document(PROJECT_MFA,
                        PermissionDocs.entry("rls_demo.docs", ROLE, PermissionDocs.select(OWNED_AT_AAL2, ALL)),
                        PermissionDocs.entry("rls_demo.secrets", ROLE, PermissionDocs.select(OWNED_AT_ROW_LEVEL, ALL))),
                PROJECT_WITH_POLICY, PermissionDocs.document(PROJECT_WITH_POLICY,
                        owned("rls_demo.docs"), open("rls_demo.notes")),
                PROJECT_NO_POLICY, PermissionDocs.document(PROJECT_NO_POLICY, open("rls_demo.docs")),
                PROJECT_CLS, PermissionDocs.document(PROJECT_CLS, docsWithoutTitle, open("rls_demo.notes")),
                PROJECT_CLS_NULL, PermissionDocs.document(PROJECT_CLS_NULL, docsWithoutTitle),
                PROJECT_NESTED, PermissionDocs.document(PROJECT_NESTED, owned("rls_demo.book"),
                        open("rls_demo.shelf")),
                PROJECT_NUMERIC, PermissionDocs.document(PROJECT_NUMERIC, PermissionDocs.entry("rls_demo.ledger", ROLE,
                        PermissionDocs.select("{\"amount\":{\"_gte\":100.00}}", ALL))),
                PROJECT_TEMPORAL, PermissionDocs.document(PROJECT_TEMPORAL, PermissionDocs.entry("rls_demo.ledger", ROLE,
                        PermissionDocs.select("{\"created_at\":{\"_gte\":\"" + DAY_AGO + "\"}}", ALL))));
    }

    @Test
    void unknownProject_isNotFound() throws Exception {
        mockMvc.perform(post("/proj-nowhere/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id } }")))
                .andExpect(status().isNotFound());
    }

    private String body(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    private String jwt(String userId, String projectId) throws Exception {
        return jwt(userId, projectId, Map.of());
    }

    private String jwt(String userId, String projectId, Map<String, Object> extraClaims) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("user@test.com")
                .claim("userId", userId)
                .claim("projectId", projectId)
                .audience("excalibase:" + projectId)
                .claim("role", "app_authenticated")
                .issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z")));
        extraClaims.forEach(claims::claim);
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims.build());
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    @Test
    void listQuery_aliceSeesOnlyOwnDocs() throws Exception {
        mockMvc.perform(post("/" + PROJECT_WITH_POLICY + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id owner_id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(2)));
    }

    @Test
    void listQuery_bobSeesOnlyOwnDocs() throws Exception {
        mockMvc.perform(post("/" + PROJECT_WITH_POLICY + "/graphql")
                        .header("Authorization", "Bearer " + jwt(BOB, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id owner_id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(1)));
    }

    @Test
    void listQuery_projectWithAnEmptyFilter_seesAllDocs() throws Exception {
        // Same table, a project whose permission has an empty filter → every row.
        mockMvc.perform(post("/" + PROJECT_NO_POLICY + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_NO_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(3)));
    }

    @Test
    void aTableTheRoleHoldsNoPermissionFor_isUnknown() throws Exception {
        mockMvc.perform(post("/" + PROJECT_NO_POLICY + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_NO_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoNotes { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void aGuestWhoseRoleHoldsNoPermission_reachesNothing() throws Exception {
        for (String query : new String[]{"{ rlsDemoDocs { id } }", "{ rlsDemoNotes { id } }"}) {
            mockMvc.perform(post("/" + PROJECT_WITH_POLICY + "/graphql")
                            .header("Authorization", "Bearer " + guestJwt(PROJECT_WITH_POLICY))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(query)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        }
    }

    /** A guest's token as excalibase-auth signs it: sub is the id, no address. */
    private String guestJwt(String projectId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("77")
                .claim("userId", 77L)
                .claim("projectId", projectId)
                .audience("excalibase:" + projectId)
                .claim("role", "guest")
                .claim("allowed_roles", List.of("guest"))
                .claim("scope", "authenticated")
                .claim("is_guest", true)
                .issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z")))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rlsDemoDocs", "rlsDemoSecrets"})
    void aPermissionRequiringAal2_admitsOnlyAnAal2Token(String field) throws Exception {
        mfaQuery(field, aliceAtLevel("aal2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data." + field, hasSize(2)));
        mfaQuery(field, aliceAtLevel("aal1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data." + field, hasSize(0)));
    }

    @ParameterizedTest
    @MethodSource("unknownAssuranceLevels")
    void aPermissionRequiringAal2_refusesATokenWithoutAKnownLevel(Object level) throws Exception {
        mfaQuery("rlsDemoDocs", aliceAtLevel(level))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("missing_session_variable"));
    }

    static Stream<Arguments> unknownAssuranceLevels() {
        return Stream.of(Arguments.of((Object) null), Arguments.of("AAL2"), Arguments.of("aal3"), Arguments.of(2));
    }

    @Test
    void rest_aPermissionRequiringAal2_admitsOnlyAnAal2Token() throws Exception {
        restDocs(aliceAtLevel("aal2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
        restDocs(aliceAtLevel("aal1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        for (String token : List.of(aliceAtLevel("AAL2"), aliceAtLevel(null))) {
            restDocs(token)
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.code").value("missing_session_variable"));
        }
    }

    private ResultActions restDocs(String token) throws Exception {
        return mockMvc.perform(get("/" + PROJECT_MFA + "/api/v1/docs").header("Accept-Profile", "rls_demo")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions mfaQuery(String field, String token) throws Exception {
        return mockMvc.perform(post("/" + PROJECT_MFA + "/graphql")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("{ " + field + " { id } }")));
    }

    /** Alice's token with the given aal claim; null leaves the claim out (MFA off, API key). */
    private String aliceAtLevel(Object level) throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put("amr", List.of("password", "totp"));
        if (level != null) {
            claims.put("aal", level);
        }
        return jwt(ALICE, PROJECT_MFA, claims);
    }

    @Test
    void listQuery_withUserFilter_combinesWithRls() throws Exception {
        // Alice asks for doc id=1 (hers) → 1 row; the RLS predicate ANDs with the
        // user filter rather than replacing it.
        mockMvc.perform(post("/" + PROJECT_WITH_POLICY + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs(where: { id: { eq: 1 } }) { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(1)));
    }

    @Test
    void listQuery_userFilterCannotEscapeRls() throws Exception {
        // Alice tries to read Bob's doc id=3 by id filter → RLS still excludes it.
        mockMvc.perform(post("/" + PROJECT_WITH_POLICY + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs(where: { id: { eq: 3 } }) { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(0)));
    }

    @Test
    void connectionQuery_isAlsoFiltered() throws Exception {
        // The connection surface must not be a bypass: Alice sees 2 edges.
        mockMvc.perform(post("/" + PROJECT_WITH_POLICY + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocsConnection { edges { node { id } } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocsConnection.edges", hasSize(2)));
    }

    @Test
    void cls_selectingAColumnOutsideTheSelectColumns_isAnUnknownField() throws Exception {
        // `title` is not among the role's select columns, so it does not exist in its schema.
        mockMvc.perform(post("/" + PROJECT_CLS + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_CLS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field(s): title")))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void cls_nonHiddenColumnsStillReturned() throws Exception {
        // Hiding `title` must not affect other columns.
        mockMvc.perform(post("/" + PROJECT_CLS + "/graphql")
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
        mockMvc.perform(post("/" + PROJECT_WITH_POLICY + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs[0].title").exists());
    }

    @Test
    void cls_nullMaskedColumnHasNoRealValue() throws Exception {
        // A former NULL mask is now "not selectable" (spec §9): the column is not on the type at all.
        mockMvc.perform(post("/" + PROJECT_CLS_NULL + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_CLS_NULL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field(s): title")));
    }




    @Test
    void nested_embeddedRelationIsFiltered_alice() throws Exception {
        // One shelf, three books (2 Alice, 1 Bob). The book policy filters the
        // embedded collection: Alice sees her 2 books nested under the shelf.
        mockMvc.perform(post("/" + PROJECT_NESTED + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_NESTED))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoShelf { id rlsDemoBook { id title } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoShelf", hasSize(1)))
                .andExpect(jsonPath("$.data.rlsDemoShelf[0].rlsDemoBook", hasSize(2)));
    }

    @Test
    void nested_embeddedRelationIsFiltered_bob() throws Exception {
        mockMvc.perform(post("/" + PROJECT_NESTED + "/graphql")
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
        mockMvc.perform(post("/" + PROJECT_NUMERIC + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_NUMERIC))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoLedger { id amount } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoLedger", hasSize(2)));
    }

    @Test
    void typePrecise_timestamptzFilters() throws Exception {
        // created_at >= (now - 1 day) → rows 2 and 3 (created now); excludes row 1
        // (10 days old). Proves a timestamp literal binds as a real timestamptz operand.
        mockMvc.perform(post("/" + PROJECT_TEMPORAL + "/graphql")
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

    // --- Multi-table requests: a policy must bind to its own table only. ---

    @Test
    void multiTable_rowPolicyAppliesOnlyToItsOwnTable() throws Exception {
        // One request, two root fields. `docs` carries the owner policy (Alice
        // sees 2 of 3); `notes` carries none in this project (all 2 rows).
        // Neither may borrow the other's filter.
        mockMvc.perform(post("/" + PROJECT_WITH_POLICY + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id owner_id } rlsDemoNotes { id owner_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(2)))
                .andExpect(jsonPath("$.data.rlsDemoNotes", hasSize(2)));
    }

    @Test
    void multiTable_restrictedTableYieldsNothingWhileOpenTableStillReturns() throws Exception {
        // Caller owns no docs: the restricted table must come back empty in the
        // same request where the unrestricted table returns every row.
        String stranger = "33333333-3333-3333-3333-333333333333";
        mockMvc.perform(post("/" + PROJECT_WITH_POLICY + "/graphql")
                        .header("Authorization", "Bearer " + jwt(stranger, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id } rlsDemoNotes { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(0)))
                .andExpect(jsonPath("$.data.rlsDemoNotes", hasSize(2)));
    }

    @Test
    void multiTable_columnPolicyAppliesOnlyToItsOwnTable() throws Exception {
        // `title` is not selectable on docs only; notes.title must survive in the
        // very same response.
        mockMvc.perform(post("/" + PROJECT_CLS + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_CLS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id } rlsDemoNotes { id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(3)))
                .andExpect(jsonPath("$.data.rlsDemoNotes", hasSize(2)))
                .andExpect(jsonPath("$.data.rlsDemoNotes[0].title").exists());
        mockMvc.perform(post("/" + PROJECT_CLS + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_CLS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id title } rlsDemoNotes { id title } }")))
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field(s): title")));
    }

    @Test
    void multiTable_aliasedSameTableTwice_bothCopiesFiltered() throws Exception {
        // Aliases must not create an unfiltered second path to the same table.
        mockMvc.perform(post("/" + PROJECT_WITH_POLICY + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ a: rlsDemoDocs { id } b: rlsDemoDocs { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.a", hasSize(2)))
                .andExpect(jsonPath("$.data.b", hasSize(2)));
    }
}
