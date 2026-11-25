package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

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
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;

/**
 * Integration test: pure multi-tenant mode — NO default spring.datasource.url.
 * App starts with empty schema. Tenant DB comes entirely from vault credentials.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiTenantNoDefaultDbTest extends EngineIntegrationTest {

  // Tenant database — the ONLY database, no default
  static final PostgreSQLContainer<?> tenantDb = new PostgreSQLContainer<>("postgres:16-alpine")
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

      PermissionDocs.serve(mockVault, "proj_appa12345", () -> PermissionDocs.document("proj_appa12345",
          PermissionDocs.entry("tenant.products", "user", PermissionDocs.everything())));
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
    mockVault.createContext("/api/vault/secrets/projects/proj_appa12345/credentials/excalibase_app",
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

  @Override
  public Map<String, String> getProperties() {
    tenantDb.start();
    registerCredentialEndpoint();

    // NO spring.datasource.url — pure multi-tenant mode
    Map<String, String> properties = EngineProperties.isolated();
    properties.put("spring.datasource.url", "");
    properties.put("app.database-type", "postgres");
    properties.put("app.max-rows", String.valueOf(30));
    properties.put("app.security.jwt-enabled", "true");
    properties.put("app.security.auth.jwks-url", String.valueOf("http://localhost:" + mockVaultPort + "/.well-known/jwks.json"));
    properties.put("app.security.multi-tenant.provisioning-url", String.valueOf("http://localhost:" + mockVaultPort + "/api"));
    properties.put("app.tenant-db.sslmode", "disable");
    properties.put("app.security.multi-tenant.provisioning-pat", "test-pat");
    properties.put("app.security.rls.policy-url", String.valueOf("http://localhost:" + mockVaultPort + "/api"));
    properties.put("app.security.rls.policy-pat", "test-pat");
    return properties;
  }

  private static final ObjectMapper mapper = new ObjectMapper();

  private String graphql(String query) throws Exception {
    return mapper.writeValueAsString(Map.of("query", query));
  }

  private static String projectIdOf(String projectName) {
    return "proj_" + projectName.replaceAll("[^a-z0-9]", "") + "12345";
  }

  private String signJwt(String orgSlug, String projectName) throws Exception {
    // Simulate the provisioner's opaque projectId ref. Deterministic per (org, project)
    // for test stability; real provisioner generates a random ref at provision time.
    String projectId = projectIdOf(projectName);
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject(String.valueOf(1L))
        .claim("projectId", projectId)
        .audience("excalibase:" + projectId)
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
    http.post("/" + projectIdOf("app-a") + "/graphql", graphql("{ tenantProducts { id name price } }"), "Authorization", "Bearer " + jwt, "Content-Type", "application/json")
        .expect(status().isOk())
        .expect(jsonPath("$.data.tenantProducts", hasSize(3)))
        .expect(jsonPath("$.data.tenantProducts[0].name", is("Widget A")));
  }

  @Test
  @Order(2)
  @DisplayName("Introspection works on tenant schema (no default DB)")
  void tenantIntrospection_returnsSchema() throws Exception {
    String jwt = signJwt("acme-corp", "app-a");
    http.post("/" + projectIdOf("app-a") + "/graphql", graphql("{ __schema { queryType { fields { name } } } }"), "Authorization", "Bearer " + jwt, "Content-Type", "application/json")
        .expect(status().isOk())
        .expect(jsonPath("$.data.__schema.queryType.fields[*].name", hasItem("tenantProducts")));
  }

  @Test
  @Order(3)
  @DisplayName("Second request uses cached schema — no vault call")
  void secondRequest_usesCachedSchema() throws Exception {
    String jwt = signJwt("acme-corp", "app-a");
    http.post("/" + projectIdOf("app-a") + "/graphql", graphql("{ tenantProducts { id name price } }"), "Authorization", "Bearer " + jwt, "Content-Type", "application/json")
        .expect(status().isOk())
        .expect(jsonPath("$.data.tenantProducts", hasSize(3)));
  }

  @Test
  @Order(4)
  @DisplayName("Mutation works on tenant DB (no default DB)")
  void tenantMutation_createProduct() throws Exception {
    String jwt = signJwt("acme-corp", "app-a");
    http.post("/" + projectIdOf("app-a") + "/graphql", graphql("mutation { createTenantProducts(input: { name: \"New Item\", price: 42.00 }) { id name price } }"), "Authorization", "Bearer " + jwt, "Content-Type", "application/json")
        .expect(status().isOk())
        .expect(jsonPath("$.data.createTenantProducts.name", is("New Item")));
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
