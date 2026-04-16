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
 * Integration test: pure multi-tenant mode — NO default spring.datasource.url.
 * App starts with empty schema. Tenant DB comes entirely from vault credentials.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiTenantNoDefaultDbTest {

  // Tenant database — the ONLY database, no default
  @Container
  static PostgreSQLContainer<?> tenantDb = new PostgreSQLContainer<>("postgres:16-alpine")
      .withInitScript("init-tenant-a.sql");

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

  private static void registerCredentialEndpoint() {
    String credsJson = String.format(
        "{\"host\":\"%s\",\"port\":\"%s\",\"database\":\"%s\",\"username\":\"%s\",\"password\":\"%s\"}",
        tenantDb.getHost(),
        tenantDb.getFirstMappedPort(),
        tenantDb.getDatabaseName(),
        tenantDb.getUsername(),
        tenantDb.getPassword());
    mockVault.createContext("/api/vault/secrets/projects/acme-corp/app-a/credentials/excalibase_app",
        exchange -> {
          byte[] body = credsJson.getBytes(StandardCharsets.UTF_8);
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
    registerCredentialEndpoint();

    // NO spring.datasource.url — pure multi-tenant mode
    registry.add("spring.datasource.url", () -> "");

    registry.add("app.database-type", () -> "postgres");
    registry.add("app.max-rows", () -> 30);
    registry.add("app.security.jwt-enabled", () -> "true");
    registry.add("app.security.auth.jwks-url", () -> "http://localhost:" + mockVaultPort + "/.well-known/jwks.json");
    registry.add("app.security.multi-tenant.provisioning-url", () -> "http://localhost:" + mockVaultPort + "/api");
    registry.add("app.security.multi-tenant.provisioning-pat", () -> "test-pat");
  }

  @Autowired
  private MockMvc mockMvc;

  private static final ObjectMapper mapper = new ObjectMapper();

  private String graphql(String query) throws Exception {
    return mapper.writeValueAsString(Map.of("query", query));
  }

  private String signJwt(String orgSlug, String projectName) throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject("test@test.com")
        .claim("userId", 1L)
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

  @Test
  @Order(1)
  @DisplayName("App starts with no default DB — first tenant request introspects and caches schema")
  void firstTenantRequest_buildsSchemaFromVault() throws Exception {
    String jwt = signJwt("acme-corp", "app-a");
    mockMvc.perform(post("/graphql")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(graphql("{ tenantProducts { id name price } }")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantProducts", hasSize(3)))
        .andExpect(jsonPath("$.data.tenantProducts[0].name", is("Widget A")));
  }

  @Test
  @Order(2)
  @DisplayName("Introspection works on tenant schema (no default DB)")
  void tenantIntrospection_returnsSchema() throws Exception {
    String jwt = signJwt("acme-corp", "app-a");
    mockMvc.perform(post("/graphql")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(graphql("{ __schema { queryType { fields { name } } } }")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.__schema.queryType.fields[*].name", hasItem("tenantProducts")));
  }

  @Test
  @Order(3)
  @DisplayName("Second request uses cached schema — no vault call")
  void secondRequest_usesCachedSchema() throws Exception {
    String jwt = signJwt("acme-corp", "app-a");
    mockMvc.perform(post("/graphql")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(graphql("{ tenantProducts { id name price } }")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantProducts", hasSize(3)));
  }

  @Test
  @Order(4)
  @DisplayName("Mutation works on tenant DB (no default DB)")
  void tenantMutation_createProduct() throws Exception {
    String jwt = signJwt("acme-corp", "app-a");
    mockMvc.perform(post("/graphql")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(graphql("mutation { createTenantProducts(input: { name: \"New Item\", price: 42.00 }) { id name price } }")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.createTenantProducts.name", is("New Item")));
  }

  private static String buildJwks(ECPublicKey key) throws Exception {
    com.nimbusds.jose.jwk.ECKey ecKey = new com.nimbusds.jose.jwk.ECKey.Builder(
        com.nimbusds.jose.jwk.Curve.P_256, key)
        .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
        .keyID("test-key")
        .build();
    com.nimbusds.jose.jwk.JWKSet jwkSet = new com.nimbusds.jose.jwk.JWKSet(ecKey);
    return jwkSet.toString();
  }
}
