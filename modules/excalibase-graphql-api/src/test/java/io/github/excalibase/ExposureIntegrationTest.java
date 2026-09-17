package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.rls.InMemoryPolicyProvider;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.PolicyProvider;
import io.github.excalibase.rls.TableGrant;
import io.github.excalibase.rls.TableGrants;
import io.github.excalibase.schema.GraphqlSchemaManager;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * End-to-end proof that exposure is enforced by <em>filtering the schema</em>:
 * a resource the caller was not granted is absent, so GraphQL answers "unknown
 * field", REST answers 404 and introspection never names it. No denial error and
 * no per-call-site check is involved anywhere in these paths.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ExposureIntegrationTest {

    private static final String PROJECT = "proj-exposure";
    private static final String ROLE = "app_authenticated";

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
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + mockVaultPort + "/.well-known/jwks.json");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PolicyProvider policyProvider;
    @Autowired
    private GraphqlSchemaManager schemaManager;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void resetExposure() {
        ((InMemoryPolicyProvider) policyProvider).putGrants(PROJECT, TableGrants.unenforced(PROJECT));
        schemaManager.evict(PROJECT);
    }

    private void enforce(TableGrant... grants) {
        ((InMemoryPolicyProvider) policyProvider)
                .putGrants(PROJECT, new TableGrants(PROJECT, true, List.of(grants)));
        schemaManager.evict(PROJECT);
    }

    private static TableGrant grantOn(String resource, Operation... operations) {
        return grantOn(resource, ROLE, operations);
    }

    private static TableGrant grantOn(String resource, String role, Operation... operations) {
        return new TableGrant("g-" + resource + "-" + role, PROJECT, resource,
                Set.of(operations), role, true);
    }

    private String body(String query) throws Exception {
        return MAPPER.writeValueAsString(Map.of("query", query));
    }

    private String jwt() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u@test.com")
                .claim("userId", "u-1")
                .claim("projectId", PROJECT)
                .claim("role", ROLE)
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
        return mockMvc.perform(post("/" + PROJECT + "/graphql")
                .header("Authorization", "Bearer " + jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(query)));
    }

    @Test
    void graphql_whenTableNotGranted_answersUnknownField() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT));

        graphql("{ publicSecrets { id token } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void graphql_whenTableGranted_returnsItsRows() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT));

        graphql("{ publicOrders { id customer } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicOrders", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    void graphql_whenExposureNotEnforced_servesTheWholeSchema() throws Exception {
        graphql("{ publicSecrets { id token } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicSecrets", hasSize(1)));
    }

    @Test
    void introspection_whenOnlySelectGranted_namesNoMutationForThatTable() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT));

        graphql("{ __schema { mutationType { fields { name } } } }")
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("createPublicOrders"))));
    }

    @Test
    void introspection_whenTableNotGranted_neverNamesIt() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT));

        graphql("{ __schema { queryType { fields { name } } } }")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("publicOrders")))
                .andExpect(content().string(not(containsString("publicSecrets"))));
    }

    @Test
    void introspection_whenInsertGranted_namesTheCreateFields() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT, Operation.INSERT));

        graphql("{ __schema { mutationType { fields { name } } } }")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("createPublicOrders")))
                .andExpect(content().string(not(containsString("deletePublicOrders"))));
    }

    @Test
    void mutation_whenDeleteNotGranted_answersUnknownField() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT, Operation.INSERT));

        graphql("mutation { deletePublicOrders(where: { id: { eq: 1 } }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletePublicOrders").doesNotExist());

        graphql("{ publicOrders(where: { id: { eq: 1 } }) { id } }")
                .andExpect(jsonPath("$.data.publicOrders", hasSize(1)));
    }

    @Test
    void mutation_whenInsertGranted_createsTheRow() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT, Operation.INSERT));

        graphql("mutation { createPublicOrders(input: { id: 90, customer: \"carol\", total: 5 }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createPublicOrders.id").value(90));
    }

    @Test
    void upsert_whenUpdateNotGranted_refusesTheOnConflictArgument() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT, Operation.INSERT));

        graphql("mutation { createPublicOrders(input: { id: 1, customer: \"mallory\", total: 1 }, "
                + "onConflict: { constraint: \"orders_pkey\", update_columns: [\"customer\"] }) { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("onConflict")));

        graphql("{ publicOrders(where: { id: { eq: 1 } }) { customer } }")
                .andExpect(jsonPath("$.data.publicOrders[0].customer").value("alice"));
    }

    @Test
    void upsert_whenUpdateGranted_rewritesTheExistingRow() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT, Operation.INSERT, Operation.UPDATE));

        graphql("mutation { createPublicOrders(input: { id: 2, customer: \"bob-renamed\", total: 1 }, "
                + "onConflict: { constraint: \"orders_pkey\", update_columns: [\"customer\"] }) { id } }")
                .andExpect(status().isOk());

        graphql("{ publicOrders(where: { id: { eq: 2 } }) { customer } }")
                .andExpect(jsonPath("$.data.publicOrders[0].customer").value("bob-renamed"));
    }

    @Test
    void grant_whenBareNameIsAmbiguousAcrossSchemas_grantsNothing() throws Exception {
        enforce(grantOn("customer", Operation.SELECT));

        graphql("{ publicCustomer { id name } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));

        graphql("{ billingCustomer { id plan } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void grant_whenBareNameIsUniqueAcrossSchemas_resolvesToThatTable() throws Exception {
        enforce(grantOn("orders", Operation.SELECT));

        graphql("{ publicOrders { id } }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicOrders", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    void function_whenGrantedWithItsReturnTable_isCallable() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT),
                grantOn("public.recent_orders", Operation.SELECT));

        graphql("{ __schema { mutationType { fields { name } } } }")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("callPublicRecentOrders")));
    }

    @Test
    void function_whenItsReturnTableIsNotGranted_isAbsent() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT),
                grantOn("public.leaky_secrets", Operation.SELECT));

        graphql("{ __schema { mutationType { fields { name } } } }")
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("LeakySecrets"))));
    }

    @Test
    void function_whenNotGranted_isAbsent() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT));

        graphql("{ __schema { mutationType { fields { name } } } }")
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("RecentOrders"))));
    }

    @Test
    void rest_whenTableNotGranted_returnsNotFound() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT));

        mockMvc.perform(get("/" + PROJECT + "/api/v1/secrets")
                        .header("Authorization", "Bearer " + jwt()).header("Accept-Profile", "public"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rest_whenTableGranted_returnsItsRows() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT));

        mockMvc.perform(get("/" + PROJECT + "/api/v1/orders")
                        .header("Authorization", "Bearer " + jwt())
                        .header("Accept-Profile", "public"))
                .andExpect(status().isOk());
    }

    @Test
    void rest_whenDeleteNotGranted_returnsNotFoundForTheDelete() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT, Operation.INSERT));

        mockMvc.perform(delete("/" + PROJECT + "/api/v1/orders?id=eq.1")
                        .header("Authorization", "Bearer " + jwt())
                        .header("Content-Profile", "public"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rest_whenInsertGranted_acceptsThePost() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT, Operation.INSERT));

        mockMvc.perform(post("/" + PROJECT + "/api/v1/orders")
                        .header("Authorization", "Bearer " + jwt())
                        .header("Content-Profile", "public")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":91,\"customer\":\"dave\",\"total\":3}"))
                .andExpect(status().isCreated());
    }

    @Test
    void rest_whenFunctionNotGranted_rpcReturnsNotFound() throws Exception {
        enforce(grantOn("public.orders", Operation.SELECT));

        mockMvc.perform(post("/" + PROJECT + "/api/v1/rpc/recent_orders")
                        .header("Authorization", "Bearer " + jwt())
                        .header("Content-Profile", "public")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private static String buildJwks(ECPublicKey key) {
        com.nimbusds.jose.jwk.ECKey ecKey = new com.nimbusds.jose.jwk.ECKey.Builder(
                com.nimbusds.jose.jwk.Curve.P_256, key)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).keyID("test-key").build();
        return new com.nimbusds.jose.jwk.JWKSet(ecKey).toString();
    }
}
