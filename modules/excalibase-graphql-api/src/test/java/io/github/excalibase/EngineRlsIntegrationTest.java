package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
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
import org.testcontainers.containers.PostgreSQLContainer;

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
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;

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
class EngineRlsIntegrationTest extends EngineIntegrationTest {

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
    private static final String PROJECT_MFA_FIXED = "proj-mfa-fixed";
    /** EXC-680: the same rule as a session variable against a fixed value, no table or column needed. */
    private static final String OWNED_AT_FIXED_AAL2 = "{\"_and\":[" + PermissionDocs.OWNED
            + ",{\"X-Excalibase-Aal\":{\"_eq\":\"aal2\"}}]}";
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

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
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
                    PROJECT_NESTED, PROJECT_NUMERIC, PROJECT_TEMPORAL, PROJECT_MFA, PROJECT_MFA_FIXED)) {
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

    @Override
    public Map<String, String> getProperties() {
        postgres.start();
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("app.database-type", "postgres");
        properties.put("app.max-rows", "30");
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.security.auth.jwks-url", String.valueOf("http://localhost:" + mockVaultPort + "/.well-known/jwks.json"));
        properties.put("app.security.multi-tenant.provisioning-url", String.valueOf("http://localhost:" + mockVaultPort + "/api"));
        properties.put("app.tenant-db.sslmode", "disable");
        properties.put("app.security.multi-tenant.provisioning-pat", "test-pat");
        properties.put("app.security.rls.policy-url", String.valueOf("http://localhost:" + mockVaultPort + "/api"));
        properties.put("app.security.rls.policy-pat", "test-pat");
        return properties;
    }


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
        return Map.ofEntries(
                Map.entry(PROJECT_MFA_FIXED, PermissionDocs.document(PROJECT_MFA_FIXED,
                        PermissionDocs.entry("rls_demo.docs", ROLE, PermissionDocs.select(OWNED_AT_FIXED_AAL2, ALL)))),
                Map.entry(PROJECT_MFA, PermissionDocs.document(PROJECT_MFA,
                        PermissionDocs.entry("rls_demo.docs", ROLE, PermissionDocs.select(OWNED_AT_AAL2, ALL)),
                        PermissionDocs.entry("rls_demo.secrets", ROLE, PermissionDocs.select(OWNED_AT_ROW_LEVEL, ALL)))),
                Map.entry(PROJECT_WITH_POLICY, PermissionDocs.document(PROJECT_WITH_POLICY,
                        owned("rls_demo.docs"), open("rls_demo.notes"))),
                Map.entry(PROJECT_NO_POLICY, PermissionDocs.document(PROJECT_NO_POLICY, open("rls_demo.docs"))),
                Map.entry(PROJECT_CLS, PermissionDocs.document(PROJECT_CLS, docsWithoutTitle, open("rls_demo.notes"))),
                Map.entry(PROJECT_CLS_NULL, PermissionDocs.document(PROJECT_CLS_NULL, docsWithoutTitle)),
                Map.entry(PROJECT_NESTED, PermissionDocs.document(PROJECT_NESTED, owned("rls_demo.book"),
                        open("rls_demo.shelf"))),
                Map.entry(PROJECT_NUMERIC, PermissionDocs.document(PROJECT_NUMERIC, PermissionDocs.entry("rls_demo.ledger", ROLE,
                        PermissionDocs.select("{\"amount\":{\"_gte\":100.00}}", ALL)))),
                Map.entry(PROJECT_TEMPORAL, PermissionDocs.document(PROJECT_TEMPORAL, PermissionDocs.entry("rls_demo.ledger", ROLE,
                        PermissionDocs.select("{\"created_at\":{\"_gte\":\"" + DAY_AGO + "\"}}", ALL)))));
    }

    @Test
    void unknownProject_isNotFound() throws Exception {
        http.post("/proj-nowhere/graphql", body("{ rlsDemoDocs { id } }"), "Content-Type", "application/json")
                .expect(status().isNotFound());
    }

    private String body(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    private String jwt(String userId, String projectId) throws Exception {
        return jwt(userId, projectId, Map.of());
    }

    private String jwt(String userId, String projectId, Map<String, Object> extraClaims) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
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
        http.post("/" + PROJECT_WITH_POLICY + "/graphql", body("{ rlsDemoDocs { id owner_id title } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocs", hasSize(2)));
    }

    @Test
    void listQuery_bobSeesOnlyOwnDocs() throws Exception {
        http.post("/" + PROJECT_WITH_POLICY + "/graphql", body("{ rlsDemoDocs { id owner_id title } }"), "Authorization", "Bearer " + jwt(BOB, PROJECT_WITH_POLICY), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocs", hasSize(1)));
    }

    @Test
    void listQuery_projectWithAnEmptyFilter_seesAllDocs() throws Exception {
        // Same table, a project whose permission has an empty filter → every row.
        http.post("/" + PROJECT_NO_POLICY + "/graphql", body("{ rlsDemoDocs { id } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_NO_POLICY), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocs", hasSize(3)));
    }

    @Test
    void aTableTheRoleHoldsNoPermissionFor_isUnknown() throws Exception {
        http.post("/" + PROJECT_NO_POLICY + "/graphql", body("{ rlsDemoNotes { id } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_NO_POLICY), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void aGuestWhoseRoleHoldsNoPermission_reachesNothing() throws Exception {
        for (String query : new String[]{"{ rlsDemoDocs { id } }", "{ rlsDemoNotes { id } }"}) {
            http.post("/" + PROJECT_WITH_POLICY + "/graphql", body(query), "Authorization", "Bearer " + guestJwt(PROJECT_WITH_POLICY), "Content-Type", "application/json")
                    .expect(status().isOk())
                    .expect(jsonPath("$.data").doesNotExist())
                    .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        }
    }

    /** A guest's token as excalibase-auth signs it: sub is the id, no address. */
    private String guestJwt(String projectId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("77")
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
                .expect(status().isOk())
                .expect(jsonPath("$.data." + field, hasSize(2)));
        mfaQuery(field, aliceAtLevel("aal1"))
                .expect(status().isOk())
                .expect(jsonPath("$.data." + field, hasSize(0)));
    }

    @ParameterizedTest
    @MethodSource("unknownAssuranceLevels")
    void aPermissionRequiringAal2_refusesATokenWithoutAKnownLevel(ClaimedLevel level) throws Exception {
        mfaQuery("rlsDemoDocs", aliceAtLevel(level.value()))
                .expect(status().isOk())
                .expect(jsonPath("$.data").doesNotExist())
                .expect(jsonPath("$.errors[0].extensions.code").value("missing_session_variable"));
    }

    /** A claim value wrapped so Micronaut does not try to resolve the test parameter as a bean. */
    record ClaimedLevel(Object value) {
    }

    static Stream<Arguments> unknownAssuranceLevels() {
        return Stream.of(new ClaimedLevel(null), new ClaimedLevel("AAL2"), new ClaimedLevel("aal3"),
                new ClaimedLevel(2)).map(Arguments::of);
    }

    @Test
    void rest_aPermissionRequiringAal2_admitsOnlyAnAal2Token() throws Exception {
        restDocs(aliceAtLevel("aal2"))
                .expect(status().isOk())
                .expect(jsonPath("$.data", hasSize(2)));
        restDocs(aliceAtLevel("aal1"))
                .expect(status().isOk())
                .expect(jsonPath("$.data", hasSize(0)));
        for (String token : List.of(aliceAtLevel("AAL2"), aliceAtLevel(null))) {
            restDocs(token)
                    .expect(status().is4xxClientError())
                    .expect(jsonPath("$.code").value("missing_session_variable"));
        }
    }

    // EXC-680: the session variable against a fixed value, through GraphQL and REST on real Postgres.
    @Test
    void aFixedAal2Check_admitsOnlyAnAal2Token() throws Exception {
        http.post("/" + PROJECT_MFA_FIXED + "/graphql", body("{ rlsDemoDocs { id } }"), "Authorization", "Bearer " + aliceAt(PROJECT_MFA_FIXED, "aal2"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocs", hasSize(2)));
        http.post("/" + PROJECT_MFA_FIXED + "/graphql", body("{ rlsDemoDocs { id } }"), "Authorization", "Bearer " + aliceAt(PROJECT_MFA_FIXED, "aal1"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocs", hasSize(0)));
        http.post("/" + PROJECT_MFA_FIXED + "/graphql", body("{ rlsDemoDocs { id } }"), "Authorization", "Bearer " + aliceAt(PROJECT_MFA_FIXED, null), "Content-Type", "application/json")
                .expect(jsonPath("$.errors[0].extensions.code").value("missing_session_variable"));
    }

    @Test
    void rest_aFixedAal2Check_admitsOnlyAnAal2Token() throws Exception {
        for (Object[] expected : new Object[][] {{"aal2", 2}, {"aal1", 0}}) {
            http.get("/" + PROJECT_MFA_FIXED + "/api/v1/docs", "Accept-Profile", "rls_demo", "Authorization", "Bearer " + aliceAt(PROJECT_MFA_FIXED, expected[0]))
                    .expect(status().isOk())
                    .expect(jsonPath("$.data", hasSize((Integer) expected[1])));
        }
        http.get("/" + PROJECT_MFA_FIXED + "/api/v1/docs", "Accept-Profile", "rls_demo", "Authorization", "Bearer " + aliceAt(PROJECT_MFA_FIXED, null))
                .expect(status().is4xxClientError())
                .expect(jsonPath("$.code").value("missing_session_variable"));
    }

    private HttpAnswer restDocs(String token) throws Exception {
        return http.get("/" + PROJECT_MFA + "/api/v1/docs", "Accept-Profile", "rls_demo", "Authorization", "Bearer " + token);
    }

    private HttpAnswer mfaQuery(String field, String token) throws Exception {
        return http.post("/" + PROJECT_MFA + "/graphql", body("{ " + field + " { id } }"), "Authorization", "Bearer " + token, "Content-Type", "application/json");
    }

    /** Alice's token with the given aal claim; null leaves the claim out (MFA off, API key). */
    private String aliceAtLevel(Object level) throws Exception {
        return aliceAt(PROJECT_MFA, level);
    }

    private String aliceAt(String project, Object level) throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put("amr", List.of("password", "totp"));
        if (level != null) {
            claims.put("aal", level);
        }
        return jwt(ALICE, project, claims);
    }

    @Test
    void listQuery_withUserFilter_combinesWithRls() throws Exception {
        // Alice asks for doc id=1 (hers) → 1 row; the RLS predicate ANDs with the
        // user filter rather than replacing it.
        http.post("/" + PROJECT_WITH_POLICY + "/graphql", body("{ rlsDemoDocs(where: { id: { eq: 1 } }) { id } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocs", hasSize(1)));
    }

    @Test
    void listQuery_userFilterCannotEscapeRls() throws Exception {
        // Alice tries to read Bob's doc id=3 by id filter → RLS still excludes it.
        http.post("/" + PROJECT_WITH_POLICY + "/graphql", body("{ rlsDemoDocs(where: { id: { eq: 3 } }) { id } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocs", hasSize(0)));
    }

    @Test
    void connectionQuery_isAlsoFiltered() throws Exception {
        // The connection surface must not be a bypass: Alice sees 2 edges.
        http.post("/" + PROJECT_WITH_POLICY + "/graphql", body("{ rlsDemoDocsConnection { edges { node { id } } } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocsConnection.edges", hasSize(2)));
    }

    @Test
    void cls_selectingAColumnOutsideTheSelectColumns_isAnUnknownField() throws Exception {
        // `title` is not among the role's select columns, so it does not exist in its schema.
        http.post("/" + PROJECT_CLS + "/graphql", body("{ rlsDemoDocs { id title } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_CLS), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field(s): title")))
                .expect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void cls_nonHiddenColumnsStillReturned() throws Exception {
        // Hiding `title` must not affect other columns.
        http.post("/" + PROJECT_CLS + "/graphql", body("{ rlsDemoDocs { id owner_id } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_CLS), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocs[0].id").exists())
                .expect(jsonPath("$.data.rlsDemoDocs[0].owner_id").exists());
    }

    @Test
    void cls_projectWithoutColumnPolicy_titleVisible() throws Exception {
        // The row-policy project has no column policy → title is present.
        http.post("/" + PROJECT_WITH_POLICY + "/graphql", body("{ rlsDemoDocs { id title } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocs[0].title").exists());
    }

    @Test
    void cls_nullMaskedColumnHasNoRealValue() throws Exception {
        // A former NULL mask is now "not selectable" (spec §9): the column is not on the type at all.
        http.post("/" + PROJECT_CLS_NULL + "/graphql", body("{ rlsDemoDocs { id title } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_CLS_NULL), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field(s): title")));
    }




    @Test
    void nested_embeddedRelationIsFiltered_alice() throws Exception {
        // One shelf, three books (2 Alice, 1 Bob). The book policy filters the
        // embedded collection: Alice sees her 2 books nested under the shelf.
        http.post("/" + PROJECT_NESTED + "/graphql", body("{ rlsDemoShelf { id rlsDemoBook { id title } } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_NESTED), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoShelf", hasSize(1)))
                .expect(jsonPath("$.data.rlsDemoShelf[0].rlsDemoBook", hasSize(2)));
    }

    @Test
    void nested_embeddedRelationIsFiltered_bob() throws Exception {
        http.post("/" + PROJECT_NESTED + "/graphql", body("{ rlsDemoShelf { id rlsDemoBook { id title } } }"), "Authorization", "Bearer " + jwt(BOB, PROJECT_NESTED), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoShelf[0].rlsDemoBook", hasSize(1)));
    }



    @Test
    void typePrecise_decimalThresholdFiltersExactly() throws Exception {
        // amount >= 100.00 → rows 1 (100.00) and 2 (250.50); excludes 3 (99.99).
        // A lossy double bind would risk boundary errors; BigDecimal is exact.
        http.post("/" + PROJECT_NUMERIC + "/graphql", body("{ rlsDemoLedger { id amount } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_NUMERIC), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoLedger", hasSize(2)));
    }

    @Test
    void typePrecise_timestamptzFilters() throws Exception {
        // created_at >= (now - 1 day) → rows 2 and 3 (created now); excludes row 1
        // (10 days old). Proves a timestamp literal binds as a real timestamptz operand.
        http.post("/" + PROJECT_TEMPORAL + "/graphql", body("{ rlsDemoLedger { id created_at } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_TEMPORAL), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoLedger", hasSize(2)));
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
        http.post("/" + PROJECT_WITH_POLICY + "/graphql", body("{ rlsDemoDocs { id owner_id } rlsDemoNotes { id owner_id } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocs", hasSize(2)))
                .expect(jsonPath("$.data.rlsDemoNotes", hasSize(2)));
    }

    @Test
    void multiTable_restrictedTableYieldsNothingWhileOpenTableStillReturns() throws Exception {
        // Caller owns no docs: the restricted table must come back empty in the
        // same request where the unrestricted table returns every row.
        String stranger = "33333333-3333-3333-3333-333333333333";
        http.post("/" + PROJECT_WITH_POLICY + "/graphql", body("{ rlsDemoDocs { id } rlsDemoNotes { id } }"), "Authorization", "Bearer " + jwt(stranger, PROJECT_WITH_POLICY), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocs", hasSize(0)))
                .expect(jsonPath("$.data.rlsDemoNotes", hasSize(2)));
    }

    @Test
    void multiTable_columnPolicyAppliesOnlyToItsOwnTable() throws Exception {
        // `title` is not selectable on docs only; notes.title must survive in the
        // very same response.
        http.post("/" + PROJECT_CLS + "/graphql", body("{ rlsDemoDocs { id } rlsDemoNotes { id title } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_CLS), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.rlsDemoDocs", hasSize(3)))
                .expect(jsonPath("$.data.rlsDemoNotes", hasSize(2)))
                .expect(jsonPath("$.data.rlsDemoNotes[0].title").exists());
        http.post("/" + PROJECT_CLS + "/graphql", body("{ rlsDemoDocs { id title } rlsDemoNotes { id title } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_CLS), "Content-Type", "application/json")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field(s): title")));
    }

    @Test
    void multiTable_aliasedSameTableTwice_bothCopiesFiltered() throws Exception {
        // Aliases must not create an unfiltered second path to the same table.
        http.post("/" + PROJECT_WITH_POLICY + "/graphql", body("{ a: rlsDemoDocs { id } b: rlsDemoDocs { id } }"), "Authorization", "Bearer " + jwt(ALICE, PROJECT_WITH_POLICY), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.a", hasSize(2)))
                .expect(jsonPath("$.data.b", hasSize(2)));
    }
}
