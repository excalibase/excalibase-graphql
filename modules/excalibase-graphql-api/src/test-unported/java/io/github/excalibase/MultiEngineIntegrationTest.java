package io.github.excalibase;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import io.github.excalibase.config.datasource.TestPki;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.FileSystemUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.Map;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One process serving a Postgres project and a MySQL project, each picked by its vault record exactly as
 * provisioning files it: no engine on the Postgres record, {@code engine: mysql} and {@code tls: required}
 * on the MySQL one (ADR 0041), whose server certificate the engine verifies against the record's sslrootcert.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiEngineIntegrationTest {

  private static final String POSTGRES_PROJECT = "proj_pgshop12345";
  private static final String MYSQL_PROJECT = "proj_myshop12345";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withInitScript("init-tenant-a.sql");

  // The MySQL tenant serves a certificate from the project's CA, which its record files as sslrootcert.
  static final Path pkiDir = createPkiDir();
  static final TestPki pki = generatePki();

  @Container
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("shop")
      .withInitScript("init-multi-engine-mysql.sql")
      .withCopyToContainer(Transferable.of(pki.serverCertificate), "/tls/server.crt")
      .withCopyToContainer(Transferable.of(pki.serverKey), "/tls/server.key")
      .withCommand("--ssl-cert=/tls/server.crt", "--ssl-key=/tls/server.key");

  static ECPrivateKey privateKey;
  static HttpServer provisioning;

  static {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      KeyPair keys = generator.generateKeyPair();
      privateKey = (ECPrivateKey) keys.getPrivate();
      provisioning = HttpServer.create(new InetSocketAddress(0), 0);
      String jwks = new JWKSet(new ECKey.Builder(Curve.P_256, (ECPublicKey) keys.getPublic())
          .keyUse(KeyUse.SIGNATURE).keyID("test-key").build()).toString();
      serve("/.well-known/jwks.json", jwks);
      PermissionDocs.serve(provisioning, POSTGRES_PROJECT, () -> PermissionDocs.document(POSTGRES_PROJECT,
          PermissionDocs.entry("tenant.products", "user", PermissionDocs.everything())));
      PermissionDocs.serve(provisioning, MYSQL_PROJECT, () -> PermissionDocs.document(MYSQL_PROJECT));
      provisioning.start();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to start the provisioning stub", e);
    }
  }

  private static Path createPkiDir() {
    try {
      return Files.createTempDirectory("multi-engine-pki");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static TestPki generatePki() {
    try {
      return TestPki.generate(pkiDir, "excalibase_app");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static String mysqlRecord() {
    try {
      return mapper.writeValueAsString(Map.of("engine", "mysql", "tls", "required",
          "host", mysql.getHost(), "port", String.valueOf(mysql.getFirstMappedPort()), "database", "shop",
          "username", mysql.getUsername(), "password", mysql.getPassword(), "sslrootcert", pki.caCertificate));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void serve(String path, String json) {
    provisioning.createContext(path, exchange -> {
      byte[] body = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    serve("/api/vault/secrets/projects/" + POSTGRES_PROJECT + "/credentials/excalibase_app",
        "{\"host\":\"" + postgres.getHost() + "\",\"port\":\"" + postgres.getFirstMappedPort()
            + "\",\"database\":\"" + postgres.getDatabaseName() + "\",\"username\":\"" + postgres.getUsername()
            + "\",\"password\":\"" + postgres.getPassword() + "\"}");
    serve("/api/vault/secrets/projects/" + MYSQL_PROJECT + "/credentials/excalibase_app", mysqlRecord());

    String api = "http://localhost:" + provisioning.getAddress().getPort() + "/api";
    registry.add("spring.datasource.url", () -> "");
    registry.add("management.health.db.enabled", () -> "false");
    registry.add("app.cors.provisioning-url", () -> api);
    registry.add("app.database-type", () -> "postgres");
    registry.add("app.security.jwt-enabled", () -> "true");
    registry.add("app.security.auth.jwks-url",
        () -> "http://localhost:" + provisioning.getAddress().getPort() + "/.well-known/jwks.json");
    registry.add("app.security.multi-tenant.provisioning-url", () -> api);
    registry.add("app.security.multi-tenant.provisioning-pat", () -> "test-pat");
    registry.add("app.tenant-db.sslmode", () -> "disable");
    registry.add("app.security.rls.policy-url", () -> api);
    registry.add("app.security.rls.policy-pat", () -> "test-pat");
  }

  @AfterAll
  static void stopProvisioning() throws IOException {
    provisioning.stop(0);
    FileSystemUtils.deleteRecursively(pkiDir);
  }

  @Autowired
  private MockMvc mockMvc;

  private static final ObjectMapper mapper = new ObjectMapper();

  private ResultActions graphql(String projectId, Map<String, Object> claims, String query) throws Exception {
    return mockMvc.perform(post("/" + projectId + "/graphql")
        .header("Authorization", "Bearer " + token(projectId, claims))
        .contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("query", query))));
  }

  private ResultActions service(String query) throws Exception {
    return graphql(MYSQL_PROJECT, Map.of("role", "service", "scope", "service"), query);
  }

  private static String token(String projectId, Map<String, Object> claims) {
    JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
        .subject("dev@example.com")
        .claim("userId", 1)
        .claim("projectId", projectId)
        .audience("excalibase:" + projectId)
        .issuer("excalibase")
        .issueTime(new Date())
        .expirationTime(new Date(System.currentTimeMillis() + 3_600_000));
    claims.forEach(builder::claim);
    SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), builder.build());
    try {
      signed.sign(new ECDSASigner(privateKey));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return signed.serialize();
  }

  @Test
  @Order(1)
  @DisplayName("the Postgres project, whose record names no engine, is served from Postgres")
  void postgresProjectAnswers() throws Exception {
    graphql(POSTGRES_PROJECT, Map.of("role", "user"), "{ tenantProducts(orderBy: { id: ASC }) { id name } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantProducts", hasSize(3)))
        .andExpect(jsonPath("$.data.tenantProducts[0].name", is("Widget A")));
    graphql(POSTGRES_PROJECT, Map.of("role", "user"),
        "mutation { createTenantProducts(input: { name: \"Pg new\", price: 1.5 }) { id name } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.createTenantProducts.name", is("Pg new")));
  }

  @Test
  @Order(2)
  @DisplayName("the MySQL project is served from MySQL: booleans, enums and datetimes as GraphQL clients expect")
  void mysqlProjectAnswersWithMappedTypes() throws Exception {
    service("{ shopGadget(orderBy: { id: ASC }) { id name in_stock size made_at seen_at } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.shopGadget", hasSize(2)))
        .andExpect(jsonPath("$.data.shopGadget[0].in_stock", is(true)))
        .andExpect(jsonPath("$.data.shopGadget[1].in_stock", is(false)))
        .andExpect(jsonPath("$.data.shopGadget[0].size", is("large")))
        .andExpect(jsonPath("$.data.shopGadget[0].made_at", is("2026-01-02T03:04:05")))
        .andExpect(jsonPath("$.data.shopGadget[0].seen_at", is("2026-01-02T03:04:05.25+00:00")))
        .andExpect(jsonPath("$.data.shopGadget[1].made_at").value((Object) null));
  }

  @Test
  @Order(3)
  @DisplayName("tinyint(1) is a Boolean and an ENUM column a GraphQL enum of its labels")
  void mysqlSchemaTypes() throws Exception {
    service("{ __type(name: \"ShopGadget\") { fields { name type { name kind ofType { name kind } } } } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'in_stock')].type.name", contains("Boolean")))
        .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'size')].type.kind", contains("ENUM")));
    service("{ __type(name: \"ShopGadgetSizeEnum\") { kind enumValues { name } } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.__type.enumValues[*].name", contains("small", "large")));
  }

  @Test
  @Order(4)
  @DisplayName("filters bind booleans and enum labels to MySQL columns")
  void mysqlFiltersOnMappedTypes() throws Exception {
    service("{ shopGadget(where: { in_stock: { eq: false } }) { name } }")
        .andExpect(jsonPath("$.data.shopGadget[*].name", contains("Cog")));
    service("{ shopGadget(where: { size: { eq: large } }) { name } }")
        .andExpect(jsonPath("$.data.shopGadget[*].name", contains("Sprocket")));
  }

  @Test
  @Order(10)
  @DisplayName("an insert reads back the row the database generated its AUTO_INCREMENT key for")
  void insertReadsBackGeneratedKey() throws Exception {
    service("mutation { createShopGadget(input: { name: \"Gear\", in_stock: false, size: large, "
        + "made_at: \"2026-03-04T05:06:07\", seen_at: \"2026-03-04T05:06:07.5Z\" }) "
        + "{ id name in_stock size made_at seen_at } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.createShopGadget.id", is(3)))
        .andExpect(jsonPath("$.data.createShopGadget.name", is("Gear")))
        .andExpect(jsonPath("$.data.createShopGadget.in_stock", is(false)))
        .andExpect(jsonPath("$.data.createShopGadget.size", is("large")))
        .andExpect(jsonPath("$.data.createShopGadget.made_at", is("2026-03-04T05:06:07")))
        .andExpect(jsonPath("$.data.createShopGadget.seen_at", is("2026-03-04T05:06:07.5+00:00")));
  }

  @Test
  @Order(11)
  @DisplayName("a bulk insert of keys the client sends reads back exactly those rows")
  void bulkInsertReadsBackSentKeys() throws Exception {
    service("mutation { createManyShopSku(inputs: [{ code: \"B-1\", label: \"b\" }, { code: \"C-1\", label: \"c\" }]) "
        + "{ code label } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.createManyShopSku[*].code", containsInAnyOrder("B-1", "C-1")));
  }

  @Test
  @Order(12)
  @DisplayName("an insert into a composite key reads back that one row")
  void insertReadsBackCompositeKey() throws Exception {
    service("mutation { createShopStock(input: { warehouse: \"east\", code: \"A-1\", qty: 3 }) "
        + "{ warehouse code qty } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.createShopStock.warehouse", is("east")))
        .andExpect(jsonPath("$.data.createShopStock.qty", is(3)));
  }

  @Test
  @Order(13)
  @DisplayName("a request's statements share one transaction: a failing row rolls back the rows before it")
  void failedBulkInsertRollsBackEveryRow() throws Exception {
    service("mutation { createManyShopSku(inputs: [{ code: \"D-1\", label: \"d\" }, { code: \"A-1\", label: \"dup\" }]) "
        + "{ code } }")
        .andExpect(jsonPath("$.errors").exists());
    service("{ shopSku(where: { code: { eq: \"D-1\" } }) { code } }")
        .andExpect(jsonPath("$.data.shopSku", hasSize(0)));
  }

  @Test
  @Order(14)
  @DisplayName("an update reads back the rows it locked and wrote, by key")
  void updateReadsBackLockedRows() throws Exception {
    service("mutation { updateShopStock(where: { code: { eq: \"A-1\" } }, input: { qty: 9 }) { warehouse qty } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.updateShopStock[*].warehouse", containsInAnyOrder("north", "south", "east")))
        .andExpect(jsonPath("$.data.updateShopStock[*].qty", contains(9, 9, 9)));
  }

  @Test
  @Order(15)
  @DisplayName("an update that changes the key reads the row back under its new key")
  void updateOfTheKeyReadsBackTheNewKey() throws Exception {
    service("mutation { updateShopSku(where: { code: { eq: \"B-1\" } }, input: { code: \"B-2\" }) { code label } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.updateShopSku[*].code", contains("B-2")))
        .andExpect(jsonPath("$.data.updateShopSku[*].label", contains("b")));
  }

  @Test
  @Order(16)
  @DisplayName("an update that matches nothing answers an empty list")
  void updateOfNothingAnswersEmpty() throws Exception {
    service("mutation { updateShopSku(where: { code: { eq: \"none\" } }, input: { label: \"x\" }) { code } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.updateShopSku", hasSize(0)));
  }

  @Test
  @Order(20)
  @DisplayName("a MySQL project serves a role under its permissions: a table it holds none on does not exist for it")
  void mysqlProjectServesARoleOnlyWhatItsPermissionsGrant() throws Exception {
    graphql(MYSQL_PROJECT, Map.of("role", "user"), "{ shopGadget { id } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").doesNotExist())
        .andExpect(jsonPath("$.errors[0].message", containsString("shopGadget")));
  }

  @Test
  @Order(21)
  @DisplayName("the Postgres project still answers after the MySQL project was served")
  void postgresProjectStillAnswers() throws Exception {
    graphql(POSTGRES_PROJECT, Map.of("role", "user"), "{ tenantProducts { id } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantProducts", hasSize(4)));
  }
}
