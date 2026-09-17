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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Engine-driven RLS + CLS on MySQL — proves the dialect-aware backtick quoting.
 * The same query-first path that serves Postgres composes a WHERE with
 * {@code `owner` = :p} (backticks) here; a regression to ANSI double quotes
 * would make MySQL read {@code 'owner'} as a string literal and silently
 * mis-filter, so the row-count assertions are the guard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MysqlEngineRlsIntegrationTest {

    private static final String PROJECT = "proj-mysql";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withInitScript("init-mysql-rls.sql");

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
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("app.database-type", () -> "mysql");
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

    /** The role every test token carries; grants are matched against it. */
    private static final String CALLER_ROLE = "app_authenticated";
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Owner policy: a row is visible iff its owner equals the caller's userId. */
    private static Policy ownerSelect() {
        return new Policy("owner-docs", "owner-docs", "test.rls_docs",
                PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("owner", FieldType.STRING, RuleOperator.EQ, "{{user_id}}")),
                List.of(Assignment.all()));
    }

    @BeforeEach
    void seed() {
        var p = (InMemoryPolicyProvider) policyProvider;
        p.put(PROJECT, List.of(ownerSelect()));
        p.putColumns(PROJECT, List.of(new ColumnPolicy(
                "hide-secret", "hide-secret", "test.rls_docs",
                java.util.Set.of("secret"), Operation.ALL, MaskMode.HIDE,
                null, null, 0, true, List.of(Assignment.all()))));
        // Exposure starts off for every test; the ones that care opt in via enforce().
        p.putGrants(PROJECT, TableGrants.unenforced(PROJECT));
        schemaManager.evict(PROJECT);
    }

    private String body(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    private String jwt(String userId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u@test.com").claim("userId", userId).claim("projectId", PROJECT)
                .audience("excalibase:" + PROJECT)
                .claim("role", "app_authenticated").issuer("excalibase")
                .issueTime(java.util.Date.from(java.time.Instant.parse("2024-01-01T00:00:00Z"))).expirationTime(java.util.Date.from(java.time.Instant.parse("2099-01-01T00:00:00Z")))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    @Test
    void rls_aliceSeesOnlyOwnRows() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id owner title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsDocs", hasSize(2)));
    }

    @Test
    void rls_bobSeesOnlyOwnRows() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id owner title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsDocs", hasSize(1)));
    }

    @Test
    void rls_userFilterCannotEscapeRls() throws Exception {
        // Alice asks for Bob's row id=3 → backtick RLS predicate still excludes it.
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs(where: { id: { eq: 3 } }) { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsDocs", hasSize(0)));
    }

    @Test
    void cls_hiddenColumnDroppedFromResponse() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id secret title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsDocs", hasSize(2)))
                .andExpect(jsonPath("$.data.testRlsDocs[0].id").exists())
                .andExpect(jsonPath("$.data.testRlsDocs[0].secret").doesNotExist());
    }

    @Test
    void insert_rowForAnotherOwner_returnsRlsDenied() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("mutation { createTestRlsDocs(input: { id: 50, owner: \"bob\", "
                                + "secret: \"s\", title: \"sneaky\" }) { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("RLS_DENIED"))
                .andExpect(jsonPath("$.errors[0].extensions.operation").value("INSERT"))
                .andExpect(jsonPath("$.errors[0].extensions.table").value("test.rls_docs"))
                .andExpect(jsonPath("$.errors[0].message", not(containsString("ERROR:"))))
                .andExpect(jsonPath("$.errors[0].message", not(containsString("SQLSTATE"))))
                .andExpect(jsonPath("$.errors[0].message", not(containsString("violates"))))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void update_reassignToAnotherOwner_returnsRlsDenied() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("mutation { updateTestRlsDocs(where: { id: { eq: 1 } }, "
                                + "input: { owner: \"bob\" }) { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("RLS_DENIED"))
                .andExpect(jsonPath("$.errors[0].extensions.operation").value("UPDATE"))
                .andExpect(jsonPath("$.errors[0].extensions.table").value("test.rls_docs"))
                .andExpect(jsonPath("$.errors[0].message", not(containsString("violates"))));
    }

    private static TableGrant grantOn(String resource, String role, Operation... operations) {
        return new TableGrant("g-" + resource + "-" + role, PROJECT, resource,
                Set.of(operations), role, true);
    }

    /** Enforces exposure for the project and drops any engine already built for it. */
    private void enforce(TableGrant... grants) {
        ((InMemoryPolicyProvider) policyProvider)
                .putGrants(PROJECT, new TableGrants(PROJECT, true, List.of(grants)));
        schemaManager.evict(PROJECT);
    }

    @Test
    void exposure_whenTableNotGranted_graphqlReportsAnUnknownField() throws Exception {
        enforce(grantOn("test.rls_docs", CALLER_ROLE, Operation.SELECT));

        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsNotes { id body } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void exposure_whenTableGranted_theQueryStillRuns() throws Exception {
        enforce(grantOn("test.rls_docs", CALLER_ROLE, Operation.SELECT));

        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id owner title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsDocs", hasSize(2)));
    }

    @Test
    void exposure_whenOnlySelectGranted_theUpdateFieldDoesNotExist() throws Exception {
        enforce(grantOn("test.rls_docs", CALLER_ROLE, Operation.SELECT));

        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("mutation { updateTestRlsDocs(where: { id: { eq: 1 } }, "
                                + "input: { title: \"renamed\" }) { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.code").doesNotExist());

        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs(where: { id: { eq: 1 } }) { title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsDocs[0].title").value("alice-doc-1"));
    }

    @Test
    void exposure_whenGrantsBelongToAnotherRole_theSchemaIsEmptyForThisCaller() throws Exception {
        enforce(grantOn("test.rls_docs", "some_other_role", Operation.SELECT));

        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void exposure_whenEnforcedWithNoGrants_nothingIsReachable() throws Exception {
        enforce();

        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("Unknown field")));
    }

    @Test
    void exposure_whenNotEnforced_theWholeSchemaIsServed() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsNotes { id body } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsNotes", hasSize(1)));
    }

    @Test
    void exposure_whenTableNotGranted_restReturnsNotFound() throws Exception {
        enforce(grantOn("test.rls_docs", CALLER_ROLE, Operation.SELECT));

        mockMvc.perform(get("/" + PROJECT + "/api/v1/rls_notes")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .header("Accept-Profile", "test"))
                .andExpect(status().isNotFound());
    }

    @Test
    void exposure_whenOnlySelectGranted_restRefusesTheWriteWithNotFound() throws Exception {
        enforce(grantOn("test.rls_docs", CALLER_ROLE, Operation.SELECT));

        mockMvc.perform(post("/" + PROJECT + "/api/v1/rls_docs")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .header("Content-Profile", "test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":90,\"owner\":\"alice\",\"secret\":\"s\",\"title\":\"t\"}"))
                .andExpect(status().isNotFound());
    }

    private static String buildJwks(ECPublicKey key) {
        com.nimbusds.jose.jwk.ECKey ecKey = new com.nimbusds.jose.jwk.ECKey.Builder(
                com.nimbusds.jose.jwk.Curve.P_256, key)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).keyID("test-key").build();
        return new com.nimbusds.jose.jwk.JWKSet(ecKey).toString();
    }
}
