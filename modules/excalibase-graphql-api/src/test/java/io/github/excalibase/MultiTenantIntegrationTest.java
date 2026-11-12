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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.hamcrest.Matchers.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;

/**
 * Integration test: Multi-tenant datasource routing via JWT claims + vault credentials.
 * Two separate Postgres containers simulate two tenants (tenant-a, tenant-b).
 * Mock vault serves credentials for each tenant.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiTenantIntegrationTest extends EngineIntegrationTest {

  // Tenant A's database (separate container)
  static final PostgreSQLContainer<?> tenantADb = new PostgreSQLContainer<>("postgres:16-alpine")
      .withInitScript("init-tenant-a.sql");

  // Tenant B's database (different schema — items instead of products)
  static final PostgreSQLContainer<?> tenantBDb = new PostgreSQLContainer<>("postgres:16-alpine")
      .withInitScript("init-tenant-b.sql");

  static ECPrivateKey privateKey;
  static ECPublicKey publicKey;
  static HttpServer mockVault;
  static int mockVaultPort;
  static final String GHOST_PROJECT = "proj_ghost12345";
  static final AtomicInteger ghostProvisioningCalls = new AtomicInteger();

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

      // Counts every provisioning call made on behalf of a project nobody provisioned.
      mockVault.createContext("/api/vault/secrets/projects/" + GHOST_PROJECT + "/", exchange -> {
        ghostProvisioningCalls.incrementAndGet();
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
      });
      mockVault.createContext("/api/projects/", exchange -> {
        if (exchange.getRequestURI().getPath().contains(GHOST_PROJECT)) {
          ghostProvisioningCalls.incrementAndGet();
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
      });

      servePermissions("proj_appa12345", "tenant.products");
      servePermissions("proj_appb12345", "tenant.items");
      mockVault.start();
    } catch (Exception e) {
      throw new RuntimeException("Failed to set up mock vault", e);
    }
  }

  /** Signed-in users and anonymous callers may do anything with the tenant's one table. */
  private static void servePermissions(String projectId, String table) {
    String document = PermissionDocs.document(projectId,
        PermissionDocs.entry(table, "user", PermissionDocs.everything()),
        PermissionDocs.entry(table, "anon", PermissionDocs.everything()));
    PermissionDocs.serve(mockVault, projectId, () -> document);
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
    mockVault.createContext("/api/vault/secrets/projects/proj_appa12345/credentials/excalibase_app",
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
    mockVault.createContext("/api/vault/secrets/projects/proj_appb12345/credentials/excalibase_app",
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

  @Override
  public Map<String, String> getProperties() {
    tenantADb.start();
    tenantBDb.start();
    // Register credential endpoints now that containers have ports
    registerCredentialEndpoints();

    // Multi-tenant mode has no default database: every project is served from its own.
    Map<String, String> properties = EngineProperties.isolated();
    properties.put("spring.datasource.url", "");
    properties.put("management.health.db.enabled", "false");
    properties.put("app.cors.provisioning-url", String.valueOf("http://localhost:" + mockVaultPort + "/api"));
    properties.put("app.database-type", "postgres");
    properties.put("app.max-rows", String.valueOf(30));
    properties.put("app.security.jwt-enabled", "true");
    properties.put("app.security.auth.jwks-url", String.valueOf("http://localhost:" + mockVaultPort + "/.well-known/jwks.json"));
    properties.put("app.security.multi-tenant.provisioning-url", String.valueOf("http://localhost:" + mockVaultPort + "/api"));
    properties.put("app.tenant-db.sslmode", "disable");
    properties.put("app.security.multi-tenant.provisioning-pat", "test-pat-token");
    properties.put("app.security.rls.policy-url", String.valueOf("http://localhost:" + mockVaultPort + "/api"));
    properties.put("app.security.rls.policy-pat", "test-pat-token");
    return properties;
  }

  private static final ObjectMapper mapper = new ObjectMapper();

  private String graphql(String query) throws Exception {
    return mapper.writeValueAsString(Map.of("query", query));
  }

  private static String projectIdOf(String projectName) {
    return "proj_" + projectName.replaceAll("[^a-z0-9]", "") + "12345";
  }

  private String signJwt(String orgSlug, String projectName, long userId) throws Exception {
    // Simulate the provisioner's opaque projectId ref. Deterministic per (org, project)
    // for test stability; real provisioner generates a random ref at provision time.
    String projectId = projectIdOf(projectName);
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject("test@test.com")
        .claim("userId", userId)
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

  // ─── No Default Project ──────────────────────────────────────────────────────

  @Test
  @Order(1)
  @DisplayName("A path project the vault does not know → 404, never the default datasource")
  void unknownProject_isNotServedFromTheDefaultDatasource() throws Exception {
    http.post("/test-proj/graphql", graphql("{ tenantProducts { id name price } }"), "Content-Type", "application/json")
        .expect(status().isNotFound())
        .expect(jsonPath("$.errors[0].extensions.code", is("project_not_found")));
    http.get("/test-proj/api/v1/products", "Accept-Profile", "tenant")
        .expect(status().isNotFound());
  }

  @Test
  @Order(2)
  @DisplayName("An anonymous caller on a real project is served that project's own database")
  void anonymousCaller_routesToTheProjectDatabase() throws Exception {
    http.post("/" + projectIdOf("app-a") + "/graphql", graphql("{ tenantProducts { id name price } }"), "Content-Type", "application/json")
        .expect(status().isOk())
        .expect(jsonPath("$.data.tenantProducts", hasSize(3)));
  }

  @Test
  @Order(3)
  @DisplayName("Repeated browser requests for an unknown project cost one provisioning call and log nothing")
  void unknownProject_repeatedFromABrowser_oneProvisioningCallAndNoLogFlood() throws Exception {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      // a housekeeper thread of some other pool warning in the same window must not fail this test
      LoggerFactory.getLogger("com.zaxxer.hikari.pool.PoolBase").warn("unrelated pool housekeeping warning");
      for (int i = 0; i < 10; i++) {
        http.post("/" + GHOST_PROJECT + "/graphql", graphql("{ tenantProducts { id } }"), "Origin", "https://app.example.com", "Content-Type", "application/json")
            .expect(status().isNotFound());
      }
    } finally {
      root.detachAppender(appender);
    }

    Assertions.assertEquals(1, ghostProvisioningCalls.get());
    Assertions.assertEquals(List.of(), appender.list.stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
        .filter(event -> event.getLoggerName().startsWith("io.github.excalibase"))
        .map(ILoggingEvent::getFormattedMessage)
        .toList());
  }

  // ─── Multi-Tenant Routing ────────────────────────────────────────────────────

  @Test
  @Order(2)
  @DisplayName("JWT with tenant A → routes to tenant A database (products table)")
  void jwtTenantA_routesToTenantADatabase() throws Exception {
    String jwt = signJwt("acme-corp", "app-a", 1);
    http.post("/" + projectIdOf("app-a") + "/graphql", graphql("{ tenantProducts { id name price } }"), "Authorization", "Bearer " + jwt, "Content-Type", "application/json")
        .expect(status().isOk())
        .expect(jsonPath("$.data.tenantProducts", hasSize(3)))
        .expect(jsonPath("$.data.tenantProducts[0].name", is("Widget A")));
  }

  @Test
  @Order(3)
  @DisplayName("JWT with tenant B → routes to tenant B database (items table, not products)")
  void jwtTenantB_routesToTenantBDatabase() throws Exception {
    String jwt = signJwt("beta-inc", "app-b", 1);
    http.post("/" + projectIdOf("app-b") + "/graphql", graphql("{ tenantItems { id title quantity } }"), "Authorization", "Bearer " + jwt, "Content-Type", "application/json")
        .expect(status().isOk())
        .expect(jsonPath("$.data.tenantItems", hasSize(2)))
        .expect(jsonPath("$.data.tenantItems[0].title", is("Item X")));
  }

  @Test
  @Order(4)
  @DisplayName("Tenant B does NOT have products table (different schema)")
  void jwtTenantB_noProductsTable() throws Exception {
    String jwt = signJwt("beta-inc", "app-b", 1);
    http.post("/" + projectIdOf("app-b") + "/graphql", graphql("{ tenantProducts { id name } }"), "Authorization", "Bearer " + jwt, "Content-Type", "application/json")
        .expect(status().isOk())
        .expect(jsonPath("$.errors").exists());
  }

  // ─── Datasource Caching ──────────────────────────────────────────────────────

  @Test
  @Order(5)
  @DisplayName("Second request to same tenant reuses cached datasource (no vault call)")
  void sameTenant_reusesCachedDatasource() throws Exception {
    String jwt = signJwt("acme-corp", "app-a", 1);

    // First request — triggers vault fetch
    http.post("/" + projectIdOf("app-a") + "/graphql", graphql("{ tenantProducts { id } }"), "Authorization", "Bearer " + jwt, "Content-Type", "application/json")
        .expect(status().isOk());

    // Second request — should use cached datasource
    http.post("/" + projectIdOf("app-a") + "/graphql", graphql("{ tenantProducts { id } }"), "Authorization", "Bearer " + jwt, "Content-Type", "application/json")
        .expect(status().isOk())
        .expect(jsonPath("$.data.tenantProducts", hasSize(3)));
  }

  // ─── Invalid Tenant ──────────────────────────────────────────────────────────

  @Test
  @Order(6)
  @DisplayName("REST defaults to the path project's own schema: the OpenAPI spec and a profile-less read")
  void rest_defaultsToTheProjectsOwnSchema() throws Exception {
    http.get("/" + projectIdOf("app-a") + "/api/v1", "Accept", "application/openapi+json")
        .expect(status().isOk())
        .expect(jsonPath("$.paths['/products']").exists());
    http.get("/" + projectIdOf("app-a") + "/api/v1/products")
        .expect(status().isOk())
        .expect(jsonPath("$.data", hasSize(3)));
  }

  @Test
  @Order(6)
  @DisplayName("JWT with unknown tenant → vault returns 404 → 404 project_not_found")
  void jwtUnknownTenant_vaultReturns404_notFound() throws Exception {
    String jwt = signJwt("no-such-org", "no-such-app", 1);
    http.post("/" + projectIdOf("no-such-app") + "/graphql", graphql("{ anything { id } }"), "Authorization", "Bearer " + jwt, "Content-Type", "application/json")
        .expect(status().isNotFound())
        .expect(jsonPath("$.errors[0].extensions.code", is("project_not_found")));
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
