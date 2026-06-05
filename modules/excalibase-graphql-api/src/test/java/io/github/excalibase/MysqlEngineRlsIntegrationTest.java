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
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
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

    @Test
    void rls_aliceSeesOnlyOwnRows() throws Exception {
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id owner title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsDocs", hasSize(2)));
    }

    @Test
    void rls_bobSeesOnlyOwnRows() throws Exception {
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt("bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id owner title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsDocs", hasSize(1)));
    }

    @Test
    void rls_userFilterCannotEscapeRls() throws Exception {
        // Alice asks for Bob's row id=3 → backtick RLS predicate still excludes it.
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs(where: { id: { eq: 3 } }) { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsDocs", hasSize(0)));
    }

    @Test
    void cls_hiddenColumnDroppedFromResponse() throws Exception {
        mockMvc.perform(post("/graphql")
                        .header("Authorization", "Bearer " + jwt("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{ testRlsDocs { id secret title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsDocs", hasSize(2)))
                .andExpect(jsonPath("$.data.testRlsDocs[0].id").exists())
                .andExpect(jsonPath("$.data.testRlsDocs[0].secret").doesNotExist());
    }



    private static String buildJwks(ECPublicKey key) {
        com.nimbusds.jose.jwk.ECKey ecKey = new com.nimbusds.jose.jwk.ECKey.Builder(
                com.nimbusds.jose.jwk.Curve.P_256, key)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).keyID("test-key").build();
        return new com.nimbusds.jose.jwk.JWKSet(ecKey).toString();
    }
}
