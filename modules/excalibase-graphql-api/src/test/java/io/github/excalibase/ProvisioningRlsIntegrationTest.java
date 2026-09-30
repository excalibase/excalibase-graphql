package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.Principal;
import org.junit.jupiter.api.AfterAll;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that the project's permission document, served over HTTP from a stub control
 * plane, drives what each role reads and writes. No Postgres-native RLS: every filter observed was
 * composed by the engine from the document.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProvisioningRlsIntegrationTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static final String PROJECT = "proj-rls";
    private static final String ROLE = "app_authenticated";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-engine-rls.sql");

    static ECPrivateKey privateKey;
    static ECPublicKey publicKey;
    static HttpServer stub;
    static int stubPort;

    /** Owner-scoped docs, membership-scoped orders, claim-scoped regions; anon holds nothing. */
    private static final String PERMISSIONS = PermissionDocs.document(PROJECT,
            PermissionDocs.entry("rls_demo.docs", ROLE,
                    PermissionDocs.select(PermissionDocs.OWNED, "\"*\""),
                    PermissionDocs.insert(PermissionDocs.OWNED, "\"*\"", "{}"),
                    PermissionDocs.update(PermissionDocs.OWNED, PermissionDocs.OWNED, "\"*\"", "{}"),
                    PermissionDocs.delete(PermissionDocs.OWNED)),
            PermissionDocs.entry("rls_demo.orders", ROLE, PermissionDocs.select(
                    "{\"rlsDemoOrgId\":{\"rlsDemoMembers\":{\"member_user\":{\"_eq\":\"X-Excalibase-User-Id\"}}}}",
                    "\"*\"")),
            PermissionDocs.entry("rls_demo.regional", ROLE,
                    PermissionDocs.select("{\"region\":{\"_eq\":\"X-Excalibase-Region\"}}", "\"*\"")));

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = gen.generateKeyPair();
            privateKey = (ECPrivateKey) kp.getPrivate();
            publicKey = (ECPublicKey) kp.getPublic();

            stub = HttpServer.create(new InetSocketAddress(0), 0);
            stubPort = stub.getAddress().getPort();
            serve("/.well-known/jwks.json", buildJwks(publicKey));
            PermissionDocs.serve(stub, PROJECT, () -> PERMISSIONS);
            stub.start();
        } catch (Exception e) {
            throw new RuntimeException("stub setup failed", e);
        }
    }

    @Test
    void rest_insertWithCheck_rollsTheRowBack() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/api/v1/docs")
                        .header("Authorization", "Bearer " + jwt(ALICE)).header("Content-Profile", "rls_demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": 93, \"owner_id\": \"" + BOB + "\", \"title\": \"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/" + PROJECT + "/api/v1/docs?id=eq.93")
                        .header("Authorization", "Bearer " + jwt(BOB)).header("Accept-Profile", "rls_demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void rest_methodWithoutPermission_isForbidden() throws Exception {
        mockMvc.perform(delete("/" + PROJECT + "/api/v1/orders?id=eq.1")
                        .header("Authorization", "Bearer " + jwt(ALICE)).header("Content-Profile", "rls_demo"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));
    }

    // ---- realtime: judged by the same select filter, probing the database for relationships ----

    @Autowired
    private AccessPlans accessPlans;

    private AccessPlans.RealtimeAccess realtimeFor(String userId, String table) {
        JwtClaims claims = new JwtClaims(userId, PROJECT, null, null, "", ROLE, null, "authenticated", 0L,
                Map.of("userId", userId), null);
        return accessPlans.realtime(null, PROJECT, new Principal(ROLE, false, claims, Map.of()), table).orElseThrow();
    }

    @Test
    void realtime_ownerFilter_deliversOnlyTheOwnersRowsWithTheirColumns() {
        AccessPlans.RealtimeAccess docs = realtimeFor(ALICE, "rls_demo.docs");

        assertThat(docs.filter().render("INSERT", Map.of("id", 1, "owner_id", ALICE, "title", "t"), docs.probes()))
                .contains(Map.of("id", 1, "owner_id", ALICE, "title", "t"));
        assertThat(docs.filter().render("INSERT", Map.of("id", 3, "owner_id", BOB, "title", "t"), docs.probes()))
                .isEmpty();
    }

    @Test
    void realtime_relationshipFilter_probesTheDatabase() {
        AccessPlans.RealtimeAccess orders = realtimeFor(ALICE, "rls_demo.orders");

        assertThat(orders.filter().render("INSERT", Map.of("id", 1, "org_id", "orgA", "title", "a"),
                orders.probes())).isPresent();
        assertThat(orders.filter().render("INSERT", Map.of("id", 3, "org_id", "orgB", "title", "b"),
                orders.probes())).isEmpty();
        assertThat(orders.filter().render("DELETE", Map.of("id", 1, "org_id", "orgA", "title", "a"),
                orders.probes())).isEmpty();
    }

    @Test
    void realtime_aTableTheRoleCannotSelect_offersNoSubscription() {
        JwtClaims claims = new JwtClaims(ALICE, PROJECT, null, null, "", ROLE, null, "authenticated", 0L,
                Map.of("userId", ALICE), null);
        assertThat(accessPlans.realtime(null, PROJECT, new Principal(ROLE, false, claims, Map.of()),
                "rls_demo.members")).isEmpty();
    }

    private static void serve(String path, String body) {
        stub.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @AfterAll
    static void teardown() {
        if (stub != null) stub.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.max-rows", () -> 30);
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + stubPort + "/.well-known/jwks.json");
        registry.add("app.security.rls.policy-url", () -> "http://localhost:" + stubPort + "/api");
        registry.add("app.security.rls.policy-pat", () -> "test-pat");
    }

    @Autowired
    private MockMvc mockMvc;

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aliceSeesOnlyOwnDocs() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id owner_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(2)));
    }

    @Test
    void bobSeesOnlyOwnDocs() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt(BOB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id owner_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(1)));
    }

    @Test
    void userFilterCannotEscapeProvisioningPolicy() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs(where: { id: { eq: 3 } }) { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(0)));
    }

    @Test
    void projectScopedUrl_authenticated_filtersByUser() throws Exception {
        // /{projectId}/graphql — project from the path, user from the token
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id owner_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoDocs", hasSize(2)));
    }

    @Test
    void projectScopedUrl_anonymous_failsClosed() throws Exception {
        // No token runs as anon, which holds no permission on docs: the table is not in its schema.
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors[0].message", containsString("rlsDemoDocs")));
    }

    @Test
    void projectScopedUrl_tokenProjectMismatch_rejected() throws Exception {
        // The path names this deployment's project; the token was minted for another.
        // The audience check catches this first, so the refusal is a 401 aud_mismatch.
        String otherProjectToken = jwtWithClaims(ALICE,
                Map.of("projectId", "some-other-project", "aud", "excalibase:some-other-project"));
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + otherProjectToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id } }")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("aud_mismatch")));
    }

    @Test
    void unknownProjectPath_isNotFound_withOrWithoutAToken() throws Exception {
        mockMvc.perform(post("/some-other-project/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id } }")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/some-other-project/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoDocs { id } }")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/some-other-project/api/v1/docs").header("Accept-Profile", "rls_demo"))
                .andExpect(status().isNotFound());
    }

    @Test
    void relationshipPolicyFiltersOrdersByMembership() throws Exception {
        // Alice ∈ orgA → sees the two orgA orders; the relationship's correlated EXISTS
        // must resolve against the compiler's aliased outer table.
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoOrders { id org_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoOrders", hasSize(2)));

        // Bob ∈ orgB → sees the one orgB order.
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt(BOB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoOrders { id org_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoOrders", hasSize(1)));
    }

    @Test
    void customClaimPolicyFiltersByRegion() throws Exception {
        // region=west claim → two west rows
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwtWithClaims(ALICE, Map.of("region", "west")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoRegional { id region } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoRegional", hasSize(2)));

        // region=east claim → one east row, same query/user shape
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwtWithClaims(BOB, Map.of("region", "east")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ rlsDemoRegional { id region } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rlsDemoRegional", hasSize(1)));
    }

    @Test
    void rest_anonymous_failsClosed() throws Exception {
        // REST applies the same permissions as GraphQL: a table anon cannot select does not exist.
        mockMvc.perform(get("/" + PROJECT + "/api/v1/docs").header("Accept-Profile", "rls_demo"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rest_authenticated_filtersByOwner() throws Exception {
        mockMvc.perform(get("/" + PROJECT + "/api/v1/docs")
                        .header("Authorization", "Bearer " + jwt(ALICE))
                        .header("Accept-Profile", "rls_demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    void rest_relationshipPolicy_filtersByMembership() throws Exception {
        // anonymous holds no permission on orders → not found
        mockMvc.perform(get("/" + PROJECT + "/api/v1/orders").header("Accept-Profile", "rls_demo"))
                .andExpect(status().isNotFound());
        // alice ∈ orgA → her two orgA orders
        mockMvc.perform(get("/" + PROJECT + "/api/v1/orders")
                        .header("Authorization", "Bearer " + jwt(ALICE))
                        .header("Accept-Profile", "rls_demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    void rpc_anUntrackedFunction_isNotFound() throws Exception {
        // Only tracked functions are reachable; this project tracks none.
        mockMvc.perform(post("/" + PROJECT + "/api/v1/rpc/any_fn")
                        .header("Content-Profile", "rls_demo")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rest_insertWithCheck_rejectsForeignOwner() throws Exception {
        // WITH-CHECK: alice cannot insert a row owned by bob.
        mockMvc.perform(post("/" + PROJECT + "/api/v1/docs")
                        .header("Authorization", "Bearer " + jwt(ALICE)).header("Content-Profile", "rls_demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": 91, \"owner_id\": \"" + BOB + "\", \"title\": \"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_check_failed"))
                .andExpect(jsonPath("$.message", not(containsString("ERROR:"))))
                .andExpect(jsonPath("$.message", not(containsString("SQLSTATE"))))
                .andExpect(jsonPath("$.message", not(containsString("violates"))));
    }

    @Test
    void rest_upsertWithCheck_rejectsForeignOwnerAsUpsert() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/api/v1/docs")
                        .header("Authorization", "Bearer " + jwt(ALICE)).header("Content-Profile", "rls_demo")
                        .header("Prefer", "resolution=merge-duplicates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": 91, \"owner_id\": \"" + BOB + "\", \"title\": \"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_check_failed"));
    }

    @Test
    void rest_insertWithCheck_allowsOwnRow() throws Exception {
        // tx=rollback: verify WITH-CHECK passes (own row allowed) without
        // persisting into the shared container (would skew the read-count tests).
        mockMvc.perform(post("/" + PROJECT + "/api/v1/docs")
                        .header("Authorization", "Bearer " + jwt(ALICE)).header("Content-Profile", "rls_demo")
                        .header("Prefer", "tx=rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": 92, \"owner_id\": \"" + ALICE + "\", \"title\": \"x\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void rest_updateWithCheck_rejectsReassignToAnotherOwner() throws Exception {
        // WITH-CHECK: alice cannot reassign her doc to bob.
        mockMvc.perform(patch("/" + PROJECT + "/api/v1/docs?id=eq.1")
                        .header("Authorization", "Bearer " + jwt(ALICE)).header("Content-Profile", "rls_demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner_id\": \"" + BOB + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_check_failed"))
                .andExpect(jsonPath("$.message", not(containsString("ERROR:"))))
                .andExpect(jsonPath("$.message", not(containsString("violates"))));
    }

    private String body(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    private String jwt(String userId) throws Exception {
        return jwtWithClaims(userId, Map.of());
    }

    private String jwtWithClaims(String userId, Map<String, Object> extra) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject("user@test.com")
                .claim("userId", userId)
                .claim("projectId", PROJECT)
                .audience("excalibase:" + PROJECT)
                .claim("role", "app_authenticated")
                .issuer("excalibase")
                .issueTime(java.util.Date.from(java.time.Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(java.util.Date.from(java.time.Instant.parse("2099-01-01T00:00:00Z")));
        extra.forEach(builder::claim);
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), builder.build());
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
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
