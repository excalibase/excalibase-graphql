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
    private static final String PROJECT_NO_GRANT = "proj-mysql-no-grant";
    private static final String PROJECT_READ_GRANT = "proj-mysql-read-grant";
    private static final String PROJECT_ROLE_GRANT = "proj-mysql-role-grant";
    private static final String AUTHENTICATED_ROLE = "app_authenticated";
    private static final String DOCS = "test.rls_docs";

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
        // The RLS/CLS project is fully exposed — it tests filtering, not exposure.
        p.putGrants(PROJECT, List.of(new TableGrant("grant-all", "grant-all", TableGrant.ALL_RESOURCES,
                Operation.ALL, List.of(Assignment.all()), true)));
        p.evict(PROJECT_NO_GRANT);
        p.putGrants(PROJECT_READ_GRANT, List.of(new TableGrant("read-docs", "read-docs", DOCS,
                Set.of(Operation.SELECT), List.of(Assignment.all()), true)));
        p.putGrants(PROJECT_ROLE_GRANT, List.of(new TableGrant("role-docs", "role-docs", DOCS,
                Operation.ALL, List.of(Assignment.role(AUTHENTICATED_ROLE)), true)));
        p.putColumns(PROJECT, List.of(new ColumnPolicy(
                "hide-secret", "hide-secret", "test.rls_docs",
                java.util.Set.of("secret"), Operation.ALL, MaskMode.HIDE,
                null, null, 0, true, List.of(Assignment.all()))));
    }

    private String body(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    private String jwt(String userId) throws Exception {
        return jwt(userId, PROJECT, AUTHENTICATED_ROLE);
    }

    private String jwt(String userId, String projectId, String role) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u@test.com").claim("userId", userId).claim("projectId", projectId)
                .audience("excalibase:" + projectId)
                .claim("role", role).issuer("excalibase")
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

    // --- Grant (exposure) layer on MySQL: same Java enforcement, no native GRANT. ---

    @Test
    void grants_tableWithoutGrant_isDenied() throws Exception {
        mockMvc.perform(post("/" + PROJECT_NO_GRANT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice", PROJECT_NO_GRANT, AUTHENTICATED_ROLE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("GRANT_DENIED"))
                .andExpect(jsonPath("$.errors[0].extensions.table").value(DOCS));
    }

    @Test
    void grants_selectGrant_returnsRows() throws Exception {
        // No row policy in this project, so the grant alone decides: all 3 rows.
        mockMvc.perform(post("/" + PROJECT_READ_GRANT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice", PROJECT_READ_GRANT, AUTHENTICATED_ROLE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id owner } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsDocs", hasSize(3)));
    }

    @Test
    void grants_selectOnlyGrant_deniesInsert() throws Exception {
        mockMvc.perform(post("/" + PROJECT_READ_GRANT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice", PROJECT_READ_GRANT, AUTHENTICATED_ROLE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("mutation { createTestRlsDocs(input: { id: 60, owner: \"alice\", "
                                + "secret: \"s\", title: \"t\" }) { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("GRANT_DENIED"))
                .andExpect(jsonPath("$.errors[0].extensions.operation").value("INSERT"));
    }

    @Test
    void grants_roleScopedGrant_deniesOtherRole() throws Exception {
        mockMvc.perform(post("/" + PROJECT_ROLE_GRANT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice", PROJECT_ROLE_GRANT, "app_anon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("GRANT_DENIED"));
    }

    @Test
    void grants_roleScopedGrant_allowsGrantedRole() throws Exception {
        mockMvc.perform(post("/" + PROJECT_ROLE_GRANT + "/graphql")
                        .header("Authorization", "Bearer " + jwt("alice", PROJECT_ROLE_GRANT, AUTHENTICATED_ROLE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsDocs", hasSize(3)));
    }

    private static String buildJwks(ECPublicKey key) {
        com.nimbusds.jose.jwk.ECKey ecKey = new com.nimbusds.jose.jwk.ECKey.Builder(
                com.nimbusds.jose.jwk.Curve.P_256, key)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).keyID("test-key").build();
        return new com.nimbusds.jose.jwk.JWKSet(ecKey).toString();
    }
}
