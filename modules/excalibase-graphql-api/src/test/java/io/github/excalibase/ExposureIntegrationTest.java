package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
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
import org.testcontainers.containers.PostgreSQLContainer;

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
import static io.github.excalibase.support.Expect.content;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;

/**
 * End-to-end proof that a role's schema is exactly its permissions (docs/features/permissions.md §5):
 * a table, column, operation or aggregate the role holds no permission for is absent, so GraphQL
 * answers "unknown field", REST 404 (or 403 for a method on a table it can see), and introspection
 * never names it. The document is served by a stub control plane and re-read on every request.
 */
class ExposureIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-exposure";
    /** The role excalibase-auth gives a signed-in end user; requests with that token run as it. */
    private static final String ROLE = "user";
    private static final String ANON = "anon";
    private static final String ALL = "\"*\"";

    private static final AtomicReference<String> PERMISSIONS = new AtomicReference<>();
    private static final AtomicLong VERSION = new AtomicLong();

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
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

    @Override
    public Map<String, String> getProperties() {
        postgres.start();
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("app.database-type", "postgres");
        properties.put("app.max-rows", String.valueOf(30));
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.project-id", String.valueOf(PROJECT));
        properties.put("app.security.auth.jwks-url", String.valueOf("http://localhost:" + mockVaultPort + "/.well-known/jwks.json"));
        properties.put("app.security.rls.policy-url", String.valueOf("http://localhost:" + mockVaultPort + "/api"));
        properties.put("app.security.rls.policy-pat", "test-pat");
        properties.put("app.security.rls.policy-ttl-ms", String.valueOf(0));
        return properties;
    }


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
                .subject(String.valueOf("u-1"))
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

    private HttpAnswer graphql(String query) throws Exception {
        return graphqlAs(ROLE, "authenticated", query);
    }

    private HttpAnswer graphqlAs(String role, String scope, String query) throws Exception {
        return http.post("/" + PROJECT + "/graphql", body(query), "Authorization", "Bearer " + jwt(role, scope), "Content-Type", "application/json");
    }

    private HttpAnswer anonymousGraphql(String query) throws Exception {
        return http.post("/" + PROJECT + "/graphql", body(query), "Content-Type", "application/json");
    }

    private HttpAnswer rest(String path) throws Exception {
        return http.get("/" + PROJECT + "/api/v1/" + path, "Authorization", "Bearer " + jwt(ROLE, "authenticated"), "Accept-Profile", "public");
    }

    // ---- tables ----

    /** A caller who presents no token runs as {@code anon}; a signed-in one as its token's role. */
    @Test
    void eachRole_seesOnlyTheTablesItMaySelect() throws Exception {
        permit(entry("public.orders", ANON, selectAll()), entry("public.secrets", ROLE, selectAll()));

        anonymousGraphql("{ publicOrders { id } }").expect(jsonPath("$.errors").doesNotExist());
        anonymousGraphql("{ publicSecrets { id token } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        graphql("{ publicSecrets { id token } }").expect(jsonPath("$.errors").doesNotExist());
        graphql("{ publicOrders { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void aRoleWithoutPermissions_hasAnEmptySchema() throws Exception {
        graphql("{ publicOrders { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        graphql("{ __schema { queryType { fields { name } } } }")
                .expect(content().string(not(containsString("publicOrders"))));
    }

    @Test
    void aCustomRole_isServedItsOwnPermissions() throws Exception {
        permit(entry("public.secrets", "editor", selectAll()));

        graphqlAs("editor", "authenticated", "{ publicSecrets { id token } }")
                .expect(jsonPath("$.data.publicSecrets", hasSize(1)));
        graphql("{ publicSecrets { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void theServiceRole_bypassesPermissions() throws Exception {
        graphqlAs("service", "service", "{ publicSecrets { id token } }")
                .expect(jsonPath("$.data.publicSecrets", hasSize(1)));
        graphqlAs("service", "service", "{ __schema { queryType { fields { name } } } }")
                .expect(content().string(not(containsString("RecentOrders"))));
    }

    @Test
    void introspection_neverNamesATableTheRoleCannotSelect() throws Exception {
        permit(entry("public.orders", ROLE, selectAll()));

        graphql("{ __schema { queryType { fields { name } } } }")
                .expect(content().string(containsString("publicOrders")))
                .expect(content().string(not(containsString("publicSecrets"))));
    }

    @Test
    void rest_aTableTheRoleCannotSelect_isNotFound() throws Exception {
        permit(entry("public.orders", ROLE, selectAll()));

        rest("secrets").expect(status().isNotFound());
        rest("orders").expect(status().isOk()).expect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(2))));
    }

    // ---- columns ----

    @Test
    void onlyTheSelectColumns_exist() throws Exception {
        permit(entry("public.orders", ROLE, select("{}", "[\"id\",\"customer\"]")));

        graphql("{ publicOrders { id customer } }")
                .expect(jsonPath("$.data.publicOrders[0].customer").exists());
        graphql("{ publicOrders { id customer total } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field(s): total")))
                .expect(jsonPath("$.data").doesNotExist());
        graphql("{ publicOrders(where: { total: { gt: 15 } }) { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("total")));
        graphql("{ publicOrders(orderBy: { total: DESC }) { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("total")));
        graphql("{ __type(name: \"PublicOrders\") { fields { name } } }")
                .expect(content().string(not(containsString("\"total\""))));
        rest("orders?total=gt.15").expect(status().isBadRequest());
        rest("orders?select=id,total").expect(status().isBadRequest());
        rest("orders").expect(content().string(not(containsString("total"))));
    }

    // ---- limit and aggregations ----

    @Test
    void theRoleLimit_capsEverySelect() throws Exception {
        permit(entry("public.orders", ROLE, select("{}", ALL, 1, false)));

        graphql("{ publicOrders(limit: 10) { id } }").expect(jsonPath("$.data.publicOrders", hasSize(1)));
        graphql("{ publicOrdersConnection(first: 10) { edges { node { id } } } }")
                .expect(jsonPath("$.data.publicOrdersConnection.edges", hasSize(1)));
        rest("orders?limit=10").expect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void aggregates_existOnlyWithAllowAggregations() throws Exception {
        permit(entry("public.orders", ROLE, select("{}", ALL, null, false)));

        graphql("{ publicOrdersAggregate { count } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        graphql("{ publicOrdersConnection { totalCount } }")
                .expect(jsonPath("$.errors[0].message", containsString("totalCount")));
        http.get("/" + PROJECT + "/api/v1/orders", "Authorization", "Bearer " + jwt(ROLE, "authenticated"), "Accept-Profile", "public", "Prefer", "count=exact")
                .expect(status().isForbidden())
                .expect(jsonPath("$.code").value("permission_denied"));

        permit(entry("public.orders", ROLE, select("{}", ALL, null, true)));
        graphql("{ publicOrdersAggregate { count } }")
                .expect(jsonPath("$.data.publicOrdersAggregate.count", greaterThanOrEqualTo(2)));
    }

    // ---- operations ----

    @Test
    void introspection_namesOnlyTheMutationsTheRoleHolds() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}")));

        graphql("{ __schema { mutationType { fields { name } } } }")
                .expect(content().string(containsString("createPublicOrders")))
                .expect(content().string(not(containsString("deletePublicOrders"))));
    }

    @Test
    void aMutationTheRoleDoesNotHold_changesNothing() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}")));

        graphql("mutation { deletePublicOrders(where: { id: { eq: 1 } }) { id } }")
                .expect(jsonPath("$.data.deletePublicOrders").doesNotExist());
        graphql("{ publicOrders(where: { id: { eq: 1 } }) { id } }")
                .expect(jsonPath("$.data.publicOrders", hasSize(1)));
    }

    @Test
    void insert_whenPermitted_createsTheRow() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}")));

        graphql("mutation { createPublicOrders(input: { id: 90, customer: \"carol\", total: 5 }) { id } }")
                .expect(jsonPath("$.data.createPublicOrders.id").value(90));
    }

    @Test
    void upsert_withoutUpdatePermission_refusesTheOnConflictArgument() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}")));

        graphql("mutation { createPublicOrders(input: { id: 1, customer: \"mallory\", total: 1 }, "
                + "onConflict: { constraint: \"orders_pkey\", update_columns: [\"customer\"] }) { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("onConflict")));
        graphql("{ publicOrders(where: { id: { eq: 1 } }) { customer } }")
                .expect(jsonPath("$.data.publicOrders[0].customer").value("alice"));
    }

    @Test
    void upsert_theUpdateHalf_reachesOnlyRowsTheUpdateFilterPasses() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}"),
                update("{\"customer\":{\"_eq\":\"bob\"}}", "{}", ALL, "{}")));

        graphql("mutation { createPublicOrders(input: { id: 2, customer: \"bob-renamed\", total: 1 }, "
                + "onConflict: { constraint: \"orders_pkey\", update_columns: [\"customer\"] }) { id } }")
                .expect(jsonPath("$.errors").doesNotExist());
        graphql("mutation { createPublicOrders(input: { id: 1, customer: \"alice-renamed\", total: 1 }, "
                + "onConflict: { constraint: \"orders_pkey\", update_columns: [\"customer\"] }) { id } }")
                .expect(jsonPath("$.errors").doesNotExist());

        graphql("{ publicOrders(where: { id: { in: [1, 2] } }, orderBy: { id: ASC }) { customer } }")
                .expect(jsonPath("$.data.publicOrders[0].customer").value("alice"))
                .expect(jsonPath("$.data.publicOrders[1].customer").value("bob-renamed"));
    }

    private HttpAnswer restPost(String body, String prefer) throws Exception {
        return http.post("/" + PROJECT + "/api/v1/orders", body, "Authorization", "Bearer " + jwt(ROLE, "authenticated"),
                "Content-Profile", "public", "Prefer", prefer, "Content-Type", "application/json");
    }

    private String customerOf(int id) throws Exception {
        return rest("orders?select=customer&id=eq." + id).json().get("data").get(0).get("customer").asText();
    }

    @Test
    void rest_bulkMerge_withoutTheUpdatePermission_isForbidden() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}")));

        restPost("[{\"id\":1,\"customer\":\"mallory\",\"total\":1},{\"id\":320,\"customer\":\"x\",\"total\":1}]",
                "return=representation, resolution=merge-duplicates")
                .expect(status().isForbidden())
                .expect(jsonPath("$.code").value("permission_denied"));
        restPost("[{\"id\":1,\"customer\":\"mallory\",\"total\":1}]", "resolution=ignore-duplicates")
                .expect(status().isCreated());
    }

    @Test
    void rest_bulkMerge_reachesOnlyRowsTheUpdateFilterPasses() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}"),
                update("{\"customer\":{\"_eq\":\"bob\"}}", "{}", ALL, "{}")));
        restPost("[{\"id\":310,\"customer\":\"bob\",\"total\":1},{\"id\":311,\"customer\":\"alice\",\"total\":1}]",
                "return=representation").expect(status().isCreated());

        restPost("[{\"id\":310,\"customer\":\"bob\",\"total\":2},{\"id\":311,\"customer\":\"hijacked\",\"total\":2}]",
                "return=representation, resolution=merge-duplicates")
                .expect(status().isCreated())
                .expect(jsonPath("$.data.length()").value(1))
                .expect(jsonPath("$.data[0].id").value(310));

        org.assertj.core.api.Assertions.assertThat(customerOf(311)).isEqualTo("alice");
    }

    @Test
    void rest_bulkMerge_whoseResultFailsTheUpdateCheck_isRefusedWhole() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}"),
                update("{\"customer\":{\"_eq\":\"bob\"}}", "{\"customer\":{\"_eq\":\"bob\"}}", ALL, "{}")));
        restPost("[{\"id\":330,\"customer\":\"bob\",\"total\":1}]", "return=representation").expect(status().isCreated());

        restPost("[{\"id\":330,\"customer\":\"carol\",\"total\":2},{\"id\":331,\"customer\":\"bob\",\"total\":2}]",
                "return=representation, resolution=merge-duplicates")
                .expect(status().isForbidden())
                .expect(jsonPath("$.code").value("permission_check_failed"));

        org.assertj.core.api.Assertions.assertThat(customerOf(330)).isEqualTo("bob");
        rest("orders?id=eq.331").expect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void rest_aMethodTheRoleDoesNotHold_isForbidden() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}")));

        http.delete("/" + PROJECT + "/api/v1/orders?id=eq.1", "Authorization", "Bearer " + jwt(ROLE, "authenticated"), "Content-Profile", "public")
                .expect(status().isForbidden())
                .expect(jsonPath("$.code").value("permission_denied"));
        http.post("/" + PROJECT + "/api/v1/orders", "{\"id\":91,\"customer\":\"dave\",\"total\":3}", "Authorization", "Bearer " + jwt(ROLE, "authenticated"), "Content-Profile", "public", "Content-Type", "application/json")
                .expect(status().isCreated());
    }

    @Test
    void delete_whenPermitted_reachesOnlyRowsItsFilterPasses() throws Exception {
        permit(entry("public.orders", ROLE, selectAll(), insert("{}", ALL, "{}"),
                PermissionDocs.delete("{\"customer\":{\"_eq\":\"erin\"}}")));
        graphql("mutation { createPublicOrders(input: { id: 95, customer: \"erin\", total: 1 }) { id } }");

        graphql("mutation { deletePublicOrders(where: { id: { in: [1, 95] } }) { id } }")
                .expect(jsonPath("$.data.deletePublicOrders", hasSize(1)))
                .expect(jsonPath("$.data.deletePublicOrders[0].id").value(95));
    }

    // ---- functions: only tracked ones are reachable (spec §6) ----

    @Test
    void anUntrackedFunction_isNotServedToARole() throws Exception {
        permit(entry("public.orders", ROLE, selectAll()));

        graphql("{ __schema { queryType { fields { name } } } }")
                .expect(content().string(not(containsString("RecentOrders"))));
        http.post("/" + PROJECT + "/api/v1/rpc/recent_orders", "{}", "Authorization", "Bearer " + jwt(ROLE, "authenticated"), "Content-Profile", "public", "Content-Type", "application/json")
                .expect(status().isNotFound());
    }

    // ---- an unreadable document ----

    @Test
    void aRefusedDocument_failsClosedForEveryRoleButService() throws Exception {
        PERMISSIONS.set("{\"projectId\":\"" + PROJECT + "\",\"version\":" + VERSION.incrementAndGet()
                + ",\"enforced\":false}");

        graphql("{ publicOrders { id } }")
                .expect(status().isServiceUnavailable())
                .expect(jsonPath("$.errors[0].extensions.code").value("permissions_unavailable"));
        rest("orders").expect(status().isServiceUnavailable());
        graphqlAs("service", "service", "{ publicOrders { id } }")
                .expect(status().isOk())
                .expect(jsonPath("$.data.publicOrders", hasSize(greaterThanOrEqualTo(2))));
    }

    private static String buildJwks(ECPublicKey key) {
        ECKey ecKey = new ECKey.Builder(Curve.P_256, key).keyUse(KeyUse.SIGNATURE).keyID("test-key").build();
        return new JWKSet(ecKey).toString();
    }
}
