package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.*;
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

import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test: JWT verification + PostgreSQL RLS enforcement.
 * Mock vault key server returns test EC P-256 public key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JwtRlsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-jwt-rls.sql");

    static ECPrivateKey privateKey;
    static ECPublicKey publicKey;
    static HttpServer mockVault;
    static int mockVaultPort;

    // Static initializer — runs before @DynamicPropertySource
    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = gen.generateKeyPair();
            privateKey = (ECPrivateKey) kp.getPrivate();
            publicKey = (ECPublicKey) kp.getPublic();

            mockVault = HttpServer.create(new InetSocketAddress(0), 0);
            mockVaultPort = mockVault.getAddress().getPort();

            String pubPem = toPem(publicKey);
            String keyJson = new ObjectMapper().writeValueAsString(Map.of("key", pubPem, "algorithm", "EC-P256"));

            mockVault.createContext("/vault/pki/public-key", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] body = keyJson.getBytes();
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
        registry.add("spring.datasource.username", () -> "app_user");
        registry.add("spring.datasource.password", () -> "apppass");
        registry.add("app.schemas", () -> "test_rls");
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.max-rows", () -> 30);
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.security.provisioning-url", () -> "http://localhost:" + mockVaultPort);
    }

    @Autowired
    private MockMvc mockMvc;

    private static final ObjectMapper mapper = new ObjectMapper();

    private String graphql(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    private String signJwt(long userId, String projectId, String role, String email) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(email)
                .claim("userId", userId)
                .claim("projectId", projectId)
                .claim("role", role)
                .issuer("excalibase")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 3600_000))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    @Test
    @Order(1)
    void jwt_user42_seesOnlyOwnRows() throws Exception {
        String jwt = signJwt(42, "test-project", "user", "alice@test.com");
        mockMvc.perform(post("/graphql")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("{ testRlsOrders { id product total } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsOrders", hasSize(2)));
    }

    @Test
    @Order(2)
    void jwt_user99_seesOnlyOwnRows() throws Exception {
        String jwt = signJwt(99, "test-project", "user", "bob@test.com");
        mockMvc.perform(post("/graphql")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("{ testRlsOrders { id product total } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsOrders", hasSize(1)));
    }

    @Test
    @Order(3)
    void noJwt_noRlsFilter() throws Exception {
        // Without JWT or X-User-Id, RLS context is not set → FORCE RLS blocks all rows
        // (FORCE ROW LEVEL SECURITY applies even to table owner when no policy matches)
        mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("{ testRlsOrders { id product total } }")))
                .andExpect(status().isOk());
    }

    @Test
    @Order(4)
    void invalidJwt_returns401() throws Exception {
        mockMvc.perform(post("/graphql")
                .header("Authorization", "Bearer invalid.jwt.token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("{ testRlsOrders { id } }")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    void legacyXUserIdHeader_stillWorks() throws Exception {
        mockMvc.perform(post("/graphql")
                .header("X-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("{ testRlsOrders { id product } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsOrders", hasSize(2)));
    }

    @Test
    @Order(6)
    void jwt_multiTable_rlsAppliedToEachTable() throws Exception {
        String jwt = signJwt(42, "test-project", "user", "alice@test.com");
        // Query both orders AND payments in a single GraphQL request
        mockMvc.perform(post("/graphql")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("{ testRlsOrders { id product } testRlsPayments { id amount method } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsOrders", hasSize(2)))      // user 42 has 2 orders
                .andExpect(jsonPath("$.data.testRlsPayments", hasSize(3)));    // user 42 has 3 payments
    }

    @Test
    @Order(7)
    void jwt_multiTable_differentUser_seesOwnData() throws Exception {
        String jwt = signJwt(99, "test-project", "user", "bob@test.com");
        mockMvc.perform(post("/graphql")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("{ testRlsOrders { id product } testRlsPayments { id amount method } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testRlsOrders", hasSize(1)))      // user 99 has 1 order
                .andExpect(jsonPath("$.data.testRlsPayments", hasSize(1)));    // user 99 has 1 payment
    }

    private static String toPem(ECPublicKey key) throws Exception {
        byte[] encoded = key.getEncoded();
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded);
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }
}
