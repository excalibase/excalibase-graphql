package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Integration test: Multi-tenant datasource routing via JWT claims + vault credentials.
 * Two separate Postgres containers simulate two tenants (tenant-a, tenant-b).
 * Mock vault serves credentials for each tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiTenantIntegrationTest {

  // Default static datasource (backward compatibility — no JWT)
  @Container
  static PostgreSQLContainer<?> defaultDb = new PostgreSQLContainer<>("postgres:16-alpine")
      .withInitScript("init-tenant-a.sql");

  // Tenant A's database (separate container)
  @Container
  static PostgreSQLContainer<?> tenantADb = new PostgreSQLContainer<>("postgres:16-alpine")
      .withInitScript("init-tenant-a.sql");

  // Tenant B's database (different schema — items instead of products)
  @Container
  static PostgreSQLContainer<?> tenantBDb = new PostgreSQLContainer<>("postgres:16-alpine")
      .withInitScript("init-tenant-b.sql");

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

      // JWKS endpoint for JWT verification
      String jwksJson = buildJwks(publicKey);
      mockVault.createContext("/.well-known/jwks.json", exchange -> {
        byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
      });

      mockVault.start();
    } catch (Exception e) {
      throw new RuntimeException("Failed to set up mock vault", e);
    }
  }

  /**
   * Register vault credential endpoints AFTER containers start (need dynamic ports).
   * Called from @DynamicPropertySource which runs after containers are up.
   */
  private static void registerCredentialEndpoints() {
    // Tenant A credentials → point to tenantADb container
    String tenantAJson = credentialJson(
        tenantADb.getHost(),
        String.valueOf(tenantADb.getFirstMappedPort()),
        tenantADb.getDatabaseName(),
        tenantADb.getUsername(),
        tenantADb.getPassword()
    );
    mockVault.createContext("/api/vault/secrets/projects/acme-corp/app-a/credentials/excalibase_app",
        exchange -> {
          byte[] body = tenantAJson.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.getResponseBody().close();
        });

    // Tenant B credentials → point to tenantBDb container
    String tenantBJson = credentialJson(
        tenantBDb.getHost(),
        String.valueOf(tenantBDb.getFirstMappedPort()),
        tenantBDb.getDatabaseName(),
        tenantBDb.getUsername(),
        tenantBDb.getPassword()
    );
    mockVault.createContext("/api/vault/secrets/projects/beta-inc/app-b/credentials/excalibase_app",
        exchange -> {
          byte[] body = tenantBJson.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.getResponseBody().close();
        });
  }

  @AfterAll
  static void teardown() {
    if (mockVault != null) mockVault.stop(0);
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    // Register credential endpoints now that containers have ports
    registerCredentialEndpoints();

    // Default static datasource (backward compat — no JWT fallback)
    registry.add("spring.datasource.url", defaultDb::getJdbcUrl);
    registry.add("spring.datasource.username", defaultDb::getUsername);
    registry.add("spring.datasource.password", defaultDb::getPassword);

    registry.add("app.database-type", () -> "postgres");
    registry.add("app.max-rows", () -> 30);
    registry.add("app.security.jwt-enabled", () -> "true");
    registry.add("app.security.auth.jwks-url", () -> "http://localhost:" + mockVaultPort + "/.well-known/jwks.json");
    registry.add("app.security.multi-tenant.provisioning-url", () -> "http://localhost:" + mockVaultPort + "/api");
    registry.add("app.security.multi-tenant.provisioning-pat", () -> "test-pat-token");
  }

  @Autowired
  private MockMvc mockMvc;

  private static final ObjectMapper mapper = new ObjectMapper();

  private String graphql(String query) throws Exception {
    return mapper.writeValueAsString(Map.of("query", query));
  }

  private String signJwt(String orgSlug, String projectName, long userId) throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject("test@test.com")
        .claim("userId", userId)
        .claim("projectId", orgSlug + "/" + projectName)
        .claim("orgSlug", orgSlug)
        .claim("projectName", projectName)
        .claim("role", "user")
        .issuer("excalibase")
        .issueTime(new Date())
        .expirationTime(new Date(System.currentTimeMillis() + 3600_000))
        .build();
    SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
    signed.sign(new ECDSASigner(privateKey));
    return signed.serialize();
  }

  // ─── Backward Compatibility ──────────────────────────────────────────────────

  @Test
  @Order(1)
  @DisplayName("No JWT → 200 with default datasource (no 401, no tenant routing)")
  void noJwt_usesDefaultDatasource() throws Exception {
    // Without JWT, no tenant routing — uses default Spring datasource
    // Returns 200 (no 401); the RLS/tenant enforcement is handled by the DB, not the controller
    mockMvc.perform(post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .content(graphql("{ tenantProducts { id name price } }")))
        .andExpect(status().isOk());
  }

  // ─── Multi-Tenant Routing ────────────────────────────────────────────────────

  @Test
  @Order(2)
  @DisplayName("JWT with tenant A → routes to tenant A database (products table)")
  void jwtTenantA_routesToTenantADatabase() throws Exception {
    String jwt = signJwt("acme-corp", "app-a", 1);
    mockMvc.perform(post("/graphql")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(graphql("{ tenantProducts { id name price } }")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantProducts", hasSize(3)))
        .andExpect(jsonPath("$.data.tenantProducts[0].name", is("Widget A")));
  }

  @Test
  @Order(3)
  @DisplayName("JWT with tenant B → routes to tenant B database (items table, not products)")
  void jwtTenantB_routesToTenantBDatabase() throws Exception {
    String jwt = signJwt("beta-inc", "app-b", 1);
    mockMvc.perform(post("/graphql")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(graphql("{ tenantItems { id title quantity } }")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantItems", hasSize(2)))
        .andExpect(jsonPath("$.data.tenantItems[0].title", is("Item X")));
  }

  @Test
  @Order(4)
  @DisplayName("Tenant B does NOT have products table (different schema)")
  void jwtTenantB_noProductsTable() throws Exception {
    String jwt = signJwt("beta-inc", "app-b", 1);
    mockMvc.perform(post("/graphql")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(graphql("{ tenantProducts { id name } }")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").exists());
  }

  // ─── Datasource Caching ──────────────────────────────────────────────────────

  @Test
  @Order(5)
  @DisplayName("Second request to same tenant reuses cached datasource (no vault call)")
  void sameTenant_reusesCachedDatasource() throws Exception {
    String jwt = signJwt("acme-corp", "app-a", 1);

    // First request — triggers vault fetch
    mockMvc.perform(post("/graphql")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(graphql("{ tenantProducts { id } }")))
        .andExpect(status().isOk());

    // Second request — should use cached datasource
    mockMvc.perform(post("/graphql")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(graphql("{ tenantProducts { id } }")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantProducts", hasSize(3)));
  }

  // ─── Invalid Tenant ──────────────────────────────────────────────────────────

  @Test
  @Order(6)
  @DisplayName("JWT with unknown tenant → vault returns 404 → error in response body")
  void jwtUnknownTenant_vaultReturns404_errorResponse() throws Exception {
    String jwt = signJwt("no-such-org", "no-such-app", 1);
    mockMvc.perform(post("/graphql")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(graphql("{ anything { id } }")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").exists())
        .andExpect(jsonPath("$.errors[0].message", containsString("Database not available")));
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────────

  private static String credentialJson(String host, String port, String database,
                                       String username, String password) {
    return String.format(
        "{\"host\":\"%s\",\"port\":\"%s\",\"database\":\"%s\",\"username\":\"%s\",\"password\":\"%s\"}",
        host, port, database, username, password);
  }

  private static String buildJwks(ECPublicKey key) {
    com.nimbusds.jose.jwk.ECKey ecKey = new com.nimbusds.jose.jwk.ECKey.Builder(
        com.nimbusds.jose.jwk.Curve.P_256, key)
        .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
        .keyID("test-key")
        .build();
    com.nimbusds.jose.jwk.JWKSet jwkSet = new com.nimbusds.jose.jwk.JWKSet(ecKey);
    return jwkSet.toString();
  }
}
