package io.github.excalibase;

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
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.excalibase.PermissionDocs.entry;
import static io.github.excalibase.PermissionDocs.insert;
import static io.github.excalibase.PermissionDocs.select;
import static io.github.excalibase.PermissionDocs.selectAll;
import static io.github.excalibase.PermissionDocs.update;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that a role's schema is exactly its permissions (docs/features/permissions.md §5):
 * a table, column, operation or aggregate the role holds no permission for is absent, so GraphQL
 * answers "unknown field", REST 404 (or 403 for a method on a table it can see), and introspection
 * never names it. The document is served by a stub control plane and re-read on every request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ExposureIntegrationTest {

    private static final String PROJECT = "proj-exposure";
    /** The role excalibase-auth gives a signed-in end user; requests with that token run as it. */
    private static final String ROLE = "user";
    private static final String ANON = "anon";
    private static final String ALL = "\"*\"";

    private static final AtomicReference<String> PERMISSIONS = new AtomicReference<>();
    private static final AtomicLong VERSION = new AtomicLong();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-exposure.sql");

    static ECPrivateKey privateKey;
    static HttpServer mockVault;
    static int mockVaultPort;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = gen.generateKeyPair();
            privateKey = (ECPrivateKey) keyPair.getPrivate();
            mockVault = HttpServer.create(new InetSocketAddress(0), 0);
            mockVaultPort = mockVault.getAddress().getPort();
            String jwksJson = buildJwks((ECPublicKey) keyPair.getPublic());
            mockVault.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] payload = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.getResponseBody().close();
            });
            PermissionDocs.serve(mockVault, PROJECT, PERMISSIONS::get);
            mockVault.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set up mock vault", e);
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
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + mockVaultPort + "/.well-known/jwks.json");
        registry.add("app.security.rls.policy-url", () -> "http://localhost:" + mockVaultPort + "/api");
        registry.add("app.security.rls.policy-pat", () -> "test-pat");
        registry.add("app.security.rls.policy-ttl-ms", () -> 0);
    }

    @Autowired
    private MockMvc mockMvc;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static void permit(String... entries) {
        PERMISSIONS.set(PermissionDocs.document(PROJECT, VERSION.incrementAndGet(), entries));
    }

    @BeforeEach
    void noPermissions() {
        permit();
    }

    private String body(String query) throws Exception {
        return MAPPER.writeValueAsString(Map.of("query", query));
    }

    private String jwt(String role, String scope) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u@test.com")
                .claim("userId", "u-1")
                .claim("projectId", PROJECT)
                .claim("role", role)
                .claim("scope", scope)
                .audience("excalibase:" + PROJECT)
                .issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z")))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    private ResultActions graphql(String query) throws Exception {
        return graphqlAs(ROLE, "authenticated", query);
    }

    private ResultActions graphqlAs(String role, String scope, String query) throws Exception {
        return mockMvc.perform(post("/" + PROJECT + "/graphql")
                .header("Authorization", "Bearer " + jwt(role, scope))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(query)));
    }

    private ResultActions anonymousGraphql(String query) throws Exception {
        return mockMvc.perform(post("/" + PROJECT + "/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(query)));
    }

    private ResultActions rest(String path) throws Exception {
        return mockMvc.perform(get("/" + PROJECT + "/api/v1/" + path)
                .header("Authorization", "Bearer " + jwt(ROLE, "authenticated")).header("Accept-Profile", "public"));
    }

    // ---- tables ----

    /** A caller who presents no token runs as {@code anon}; a signed-in one as its token's role. */
    @Test
    void eachRole_seesOnlyTheTablesItMaySelect() throws Exception {
        permit(entry("public.orders", ANON, selectAll()), entry("public.secrets", ROLE, selectAll()));

        anonymousGraphql("{ publicOrders { id } }").andExpect(jsonPath("$.errors").doesNotExist());
        anonymousGraphql("{ publicSecrets { id token } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        graphql("{ publicSecrets { id token } }").andExpect(jsonPath("$.errors").doesNotExist());
        graphql("{ publicOrders { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void aRoleWithoutPermissions_hasAnEmptySchema() throws Exception {
        graphql("{ publicOrders { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        graphql("{ __schema { queryType { fields { name } } } }")
                .andExpect(content().string(not(containsString("publicOrders"))));
    }

    @Test
    void aCustomRole_isServedItsOwnPermissions() throws Exception {
        permit(entry("public.secrets", "editor", selectAll()));

        graphqlAs("editor", "authenticated", "{ publicSecrets { id token } }")
                .andExpect(jsonPath("$.data.publicSecrets", hasSize(1)));
        graphql("{ publicSecrets { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void theServiceRole_bypassesPermissions() throws Exception {
        graphqlAs("service", "service", "{ publicSecrets { id token } }")
                .andExpect(jsonPath("$.data.publicSecrets", hasSize(1)));
        graphqlAs("service", "service", "{ __schema { mutationType { fields { name } } } }")
                .andExpect(content().string(containsString("callPublicRecentOrders")));
    }

    @Test
    void introspection_neverNamesATableTheRoleCannotSelect() throws Exception {
        permit(entry("public.orders", ROLE, selectAll()));

        graphql("{ __schema { queryType { fields { name } } } }")
                .andExpect(content().string(containsString("publicOrders")))
                .andExpect(content().string(not(containsString("publicSecrets"))));
    }

    @Test
    void rest_aTableTheRoleCannotSelect_isNotFound() throws Exception {
        permit(entry("public.orders", ROLE, selectAll()));

        rest("secrets").andExpect(status().isNotFound());
        rest("orders").andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(2))));
    }

    // ---- columns ----

    @Test
    void onlyTheSelectColumns_exist() throws Exception {
        permit(entry("public.orders", ROLE, select("{}", "[\"id\",\"customer\"]")));

        graphql("{ publicOrders { id customer } }")
                .andExpect(jsonPath("$.data.publicOrders[0].customer").exists());
        graphql("{ publicOrders { id customer total } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field(s): total")))
                .andExpect(jsonPath("$.data").doesNotExist());
        graphql("{ publicOrders(where: { total: { gt: 15 } }) { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("total")));
        graphql("{ publicOrders(orderBy: { total: DESC }) { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("total")));
        graphql("{ __type(name: \"PublicOrders\") { fields { name } } }")
                .andExpect(content().string(not(containsString("\"total\""))));
        rest("orders?total=gt.15").andExpect(status().isBadRequest());
        rest("orders?select=id,total").andExpect(status().isBadRequest());
        rest("orders").andExpect(content().string(not(containsString("total"))));
    }

    // ---- limit and aggregations ----

    @Test
    void theRoleLimit_capsEverySelect() throws Exception {
        permit(entry("public.orders", ROLE, select("{}", ALL, 1, false)));

        graphql("{ publicOrders(limit: 10) { id } }").andExpect(jsonPath("$.data.publicOrders", hasSize(1)));
        graphql("{ publicOrdersConnection(first: 10) { edges { node { id } } } }")
                .andExpect(jsonPath("$.data.publicOrdersConnection.edges", hasSize(1)));
        rest("orders?limit=10").andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void aggregates_existOnlyWithAllowAggregations() throws Exception {
        permit(entry("public.orders", ROLE, select("{}", ALL, null, false)));

        graphql("{ publicOrdersAggregate { count } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        graphql("{ publicOrdersConnection { totalCount } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("totalCount")));
        mockMvc.perform(get("/" + PROJECT + "/api/v1/orders")
                        .header("Authorization", "Bearer " + jwt(ROLE, "authenticated"))
                        .header("Accept-Profile", "public").header("Prefer", "count=exact"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));

        permit(entry("public.orders", ROLE, select("{}", ALL, null, true)));
        graphql("{ publicOrdersAggregate { count } }")
                .andExpect(jsonPath("$.data.publicOrdersAggregate.count", greaterThanOrEqualTo(2)));
    }

    // ---- operations ----

    @Test
    void introspection_namesOnlyTheMutationsTheRoleHolds() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}")));

        graphql("{ __schema { mutationType { fields { name } } } }")
                .andExpect(content().string(containsString("createPublicOrders")))
                .andExpect(content().string(not(containsString("deletePublicOrders"))));
    }

    @Test
    void aMutationTheRoleDoesNotHold_changesNothing() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}")));

        graphql("mutation { deletePublicOrders(where: { id: { eq: 1 } }) { id } }")
                .andExpect(jsonPath("$.data.deletePublicOrders").doesNotExist());
        graphql("{ publicOrders(where: { id: { eq: 1 } }) { id } }")
                .andExpect(jsonPath("$.data.publicOrders", hasSize(1)));
    }

    @Test
    void insert_whenPermitted_createsTheRow() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}")));

        graphql("mutation { createPublicOrders(input: { id: 90, customer: \"carol\", total: 5 }) { id } }")
                .andExpect(jsonPath("$.data.createPublicOrders.id").value(90));
    }

    @Test
    void upsert_withoutUpdatePermission_refusesTheOnConflictArgument() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}")));

        graphql("mutation { createPublicOrders(input: { id: 1, customer: \"mallory\", total: 1 }, "
                + "onConflict: { constraint: \"orders_pkey\", update_columns: [\"customer\"] }) { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("onConflict")));
        graphql("{ publicOrders(where: { id: { eq: 1 } }) { customer } }")
                .andExpect(jsonPath("$.data.publicOrders[0].customer").value("alice"));
    }

    @Test
    void upsert_theUpdateHalf_reachesOnlyRowsTheUpdateFilterPasses() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}"),
                update("{\"customer\":{\"_eq\":\"bob\"}}", "{}", ALL, "{}")));

        graphql("mutation { createPublicOrders(input: { id: 2, customer: \"bob-renamed\", total: 1 }, "
                + "onConflict: { constraint: \"orders_pkey\", update_columns: [\"customer\"] }) { id } }")
                .andExpect(jsonPath("$.errors").doesNotExist());
        graphql("mutation { createPublicOrders(input: { id: 1, customer: \"alice-renamed\", total: 1 }, "
                + "onConflict: { constraint: \"orders_pkey\", update_columns: [\"customer\"] }) { id } }")
                .andExpect(jsonPath("$.errors").doesNotExist());

        graphql("{ publicOrders(where: { id: { in: [1, 2] } }, orderBy: { id: ASC }) { customer } }")
                .andExpect(jsonPath("$.data.publicOrders[0].customer").value("alice"))
                .andExpect(jsonPath("$.data.publicOrders[1].customer").value("bob-renamed"));
    }

    @Test
    void rest_aMethodTheRoleDoesNotHold_isForbidden() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}")));

        mockMvc.perform(delete("/" + PROJECT + "/api/v1/orders?id=eq.1")
                        .header("Authorization", "Bearer " + jwt(ROLE, "authenticated"))
                        .header("Content-Profile", "public"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));
        mockMvc.perform(post("/" + PROJECT + "/api/v1/orders")
                        .header("Authorization", "Bearer " + jwt(ROLE, "authenticated"))
                        .header("Content-Profile", "public")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":91,\"customer\":\"dave\",\"total\":3}"))
                .andExpect(status().isCreated());
    }

    @Test
    void delete_whenPermitted_reachesOnlyRowsItsFilterPasses() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}"),
                PermissionDocs.delete("{\"customer\":{\"_eq\":\"erin\"}}")));
        graphql("mutation { createPublicOrders(input: { id: 95, customer: \"erin\", total: 1 }) { id } }");

        graphql("mutation { deletePublicOrders(where: { id: { in: [1, 95] } }) { id } }")
                .andExpect(jsonPath("$.data.deletePublicOrders", hasSize(1)))
                .andExpect(jsonPath("$.data.deletePublicOrders[0].id").value(95));
    }

    // ---- functions (spec §6 is not in force yet: no role but service reaches one) ----

    @Test
    void functions_areNotServedToARole() throws Exception {
        permit(entry("public.orders", ROLE, selectAll()));

        graphql("{ __schema { mutationType { fields { name } } } }")
                .andExpect(content().string(not(containsString("RecentOrders"))));
        mockMvc.perform(post("/" + PROJECT + "/api/v1/rpc/recent_orders")
                        .header("Authorization", "Bearer " + jwt(ROLE, "authenticated"))
                        .header("Content-Profile", "public")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    // ---- an unreadable document ----

    @Test
    void aRefusedDocument_failsClosedForEveryRoleButService() throws Exception {
        PERMISSIONS.set("{\"projectId\":\"" + PROJECT + "\",\"version\":" + VERSION.incrementAndGet()
                + ",\"enforced\":false}");

        graphql("{ publicOrders { id } }")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("permissions_unavailable"));
        rest("orders").andExpect(status().isServiceUnavailable());
        graphqlAs("service", "service", "{ publicOrders { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicOrders", hasSize(greaterThanOrEqualTo(2))));
    }

    private static String buildJwks(ECPublicKey key) {
        ECKey ecKey = new ECKey.Builder(Curve.P_256, key).keyUse(KeyUse.SIGNATURE).keyID("test-key").build();
        return new JWKSet(ecKey).toString();
    }
}
