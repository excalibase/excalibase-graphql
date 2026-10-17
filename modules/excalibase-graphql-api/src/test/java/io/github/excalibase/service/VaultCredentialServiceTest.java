package io.github.excalibase.service;

import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.security.TokenFileSource;
import io.github.excalibase.security.ProjectWithoutDatabaseException;
import io.github.excalibase.security.UnknownProjectException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VaultCredentialServiceTest {

  private static HttpServer mockVault;
  private static int port;
  private static volatile String authHeaderSeen;
  private VaultCredentialService service;
  private final long[] now = {1_000L};

  @BeforeAll
  static void startMockVault() throws Exception {
    mockVault = HttpServer.create(new InetSocketAddress(0), 0);
    port = mockVault.getAddress().getPort();

    // Vault path is projectId-only.
    mockVault.createContext("/api/vault/secrets/projects/app-a/credentials/excalibase_app", exchange -> {
      authHeaderSeen = exchange.getRequestHeaders().getFirst("Authorization");
      String json = """
          {"host":"app-a-postgres-rw.svc.local","port":"5432","database":"app","username":"excalibase_app","password":"secret123"}
          """;
      byte[] body = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });

    // 404 for unknown project
    mockVault.createContext("/api/vault/secrets/projects/unknown-app/credentials/excalibase_app", exchange -> {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
    });

    mockVault.createContext("/api/vault/secrets/projects/tls-app/credentials/excalibase_app", exchange -> {
      String json = """
          {"host":"tls-app-postgres-rw.ns.svc.cluster.local","port":"5432","database":"app",
           "username":"excalibase_app","password":"secret123",
           "sslcert":"CERT-PEM","sslkey":"KEY-PEM","sslrootcert":"ROOT-PEM"}
          """;
      byte[] body = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });

    // The record provisioning files for a MySQL project (server-go mysqlCredentialRecord).
    serveRecord("mysql-app", """
        {"engine":"mysql","tls":"required","host":"mysql-app-primary.ns.svc.cluster.local","port":"3306",
         "database":"app","username":"excalibase_app","password":"secret123","sslrootcert":"CA-PEM"}
        """);
    serveRecord("mysql-no-ca", """
        {"engine":"mysql","tls":"required","host":"h","port":"3306","database":"app",
         "username":"excalibase_app","password":"p"}
        """);
    serveRecord("mysql-plain", """
        {"engine":"mysql","host":"h","port":"3306","database":"app","username":"excalibase_app","password":"p"}
        """);
    serveRecord("mongo-app", """
        {"engine":"mongodb","host":"h","port":"27017","database":"app","username":"u","password":"p"}
        """);

    // A project created without a database: provisioning answers 409.
    mockVault.createContext("/api/vault/secrets/projects/apps-only/credentials/excalibase_app", exchange -> {
      byte[] body = "{\"error\":\"project has no database\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(409, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });

    mockVault.createContext("/api/vault/secrets/projects/broken-app/credentials/excalibase_app", exchange -> {
      exchange.sendResponseHeaders(500, -1);
      exchange.close();
    });

    mockVault.start();
  }

  private static void serveRecord(String projectId, String json) {
    mockVault.createContext("/api/vault/secrets/projects/" + projectId + "/credentials/excalibase_app", exchange -> {
      byte[] body = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
  }

  @AfterAll
  static void stopMockVault() {
    if (mockVault != null) mockVault.stop(0);
  }

  @BeforeEach
  void setUp() {
    service = new VaultCredentialService("http://localhost:" + port + "/api", "test-pat");
  }

  @Test
  @DisplayName("fetchCredentials returns parsed VaultCredentials on 200")
  void fetchCredentials_success_returnsCredentials() {
    VaultCredentials creds = service.fetchCredentials("duc-corp", "app-a");

    assertEquals("app-a-postgres-rw.svc.local", creds.host());
    assertEquals("5432", creds.port());
    assertEquals("app", creds.database());
    assertEquals("excalibase_app", creds.username());
    assertEquals("secret123", creds.password());
  }

  @Test
  @DisplayName("a record without certificate fields carries no TLS material")
  void fetchCredentials_passwordOnlyRecord_hasNoTls() {
    assertNull(service.fetchCredentials("duc-corp", "app-a").tls());
  }

  @Test
  @DisplayName("sslcert, sslkey and sslrootcert are read from the record")
  void fetchCredentials_readsClientCertificateFields() {
    TenantTlsCredentials tls = service.fetchCredentials("duc-corp", "tls-app").tls();

    assertEquals(new TenantTlsCredentials("CERT-PEM", "KEY-PEM", "ROOT-PEM"), tls);
  }

  @Test
  @DisplayName("fetchCredentials builds correct JDBC URL")
  void fetchCredentials_buildsJdbcUrl() {
    VaultCredentials creds = service.fetchCredentials("duc-corp", "app-a");
    assertEquals("jdbc:postgresql://app-a-postgres-rw.svc.local:5432/app", creds.jdbcUrl());
  }

  @Test
  @DisplayName("a record without an engine is a Postgres project, as provisioning files every Postgres record")
  void fetchCredentials_recordWithoutEngine_isPostgres() {
    assertEquals(DatabaseEngine.POSTGRES, service.fetchCredentials("duc-corp", "app-a").engine());
  }

  @Test
  @DisplayName("a MySQL record is served by Connector/J at its host, port and database")
  void fetchCredentials_mysqlRecord_isMysql() {
    VaultCredentials creds = service.fetchCredentials("duc-corp", "mysql-app");

    assertEquals(DatabaseEngine.MYSQL, creds.engine());
    assertEquals("jdbc:mysql://mysql-app-primary.ns.svc.cluster.local:3306/app", creds.jdbcUrl());
  }

  @Test
  @DisplayName("a MySQL record's sslrootcert is the server CA alone: no client certificate is modelled")
  void fetchCredentials_mysqlRecord_readsServerCa() {
    VaultCredentials creds = service.fetchCredentials("duc-corp", "mysql-app");

    assertEquals("CA-PEM", creds.serverCa());
    assertNull(creds.tls());
  }

  @Test
  @DisplayName("a MySQL record without sslrootcert parses with no CA, which the pool then refuses")
  void fetchCredentials_mysqlRecordWithoutCa_hasNoServerCa() {
    assertNull(service.fetchCredentials("duc-corp", "mysql-no-ca").serverCa());
  }

  @Test
  @DisplayName("a Postgres record keeps its root certificate with its client certificate, not as a server CA")
  void fetchCredentials_postgresRecord_hasNoSeparateServerCa() {
    assertNull(service.fetchCredentials("duc-corp", "tls-app").serverCa());
  }

  @Test
  @DisplayName("a MySQL record that does not require TLS is refused, never connected in plain text")
  void fetchCredentials_mysqlRecordWithoutTls_isRefused() {
    VaultCredentialException refused = assertThrows(VaultCredentialException.class,
        () -> service.fetchCredentials("duc-corp", "mysql-plain"));
    assertTrue(refused.getMessage().contains("tls"), refused.getMessage());
  }

  @Test
  @DisplayName("an engine the engine cannot serve is refused, never served as Postgres")
  void fetchCredentials_unknownEngine_isRefused() {
    VaultCredentialException refused = assertThrows(VaultCredentialException.class,
        () -> service.fetchCredentials("duc-corp", "mongo-app"));
    assertTrue(refused.getMessage().contains("mongodb"), refused.getMessage());
  }

  @Test
  @DisplayName("fetchCredentials reports a 409 as a project that has no database")
  void fetchCredentials_conflict_isProjectWithoutDatabase() {
    assertThrows(ProjectWithoutDatabaseException.class,
        () -> service.fetchCredentials("org", "apps-only"));
  }

  @Test
  @DisplayName("fetchCredentials reports a 404 as an unknown project")
  void fetchCredentials_notFound_isUnknownProject() {
    assertThrows(UnknownProjectException.class,
        () -> service.fetchCredentials("unknown-org", "unknown-app"));
  }

  @Test
  @DisplayName("fetchCredentials reports any other non-200 as unavailable, not unknown")
  void fetchCredentials_serverError_isUnavailable() {
    var ex = assertThrows(VaultCredentialException.class,
        () -> service.fetchCredentials("any-org", "broken-app"));
    assertTrue(ex.getMessage().contains("Database not available"));
  }

  @Test
  @DisplayName("fetchCredentials throws on unreachable vault")
  void fetchCredentials_unreachable_throws() {
    var unreachable = new VaultCredentialService("http://localhost:1", "test-pat");
    assertThrows(VaultCredentialException.class,
        () -> unreachable.fetchCredentials("any-org", "any-app"));
  }

  @Test
  @DisplayName("fetchCredentials sends Authorization Bearer header")
  void fetchCredentials_sendsAuthHeader() {
    var serviceWithPat = new VaultCredentialService("http://localhost:" + port + "/api", "my-secret-pat");
    VaultCredentials creds = serviceWithPat.fetchCredentials("duc-corp", "app-a");
    assertNotNull(creds);
    assertEquals("Bearer my-secret-pat", authHeaderSeen);
  }

  @Test
  @DisplayName("a rotated token file is picked up without reconstructing the service")
  void fetchCredentials_picksUpRotatedTokenFile(@TempDir Path dir) throws IOException {
    Path tokenFile = Files.writeString(dir.resolve("graphql-token"), "old-pat\n");
    var serviceWithFile = new VaultCredentialService("http://localhost:" + port + "/api",
        new TokenFileSource(tokenFile.toString(), "unused-literal", () -> now[0]));
    serviceWithFile.fetchCredentials("duc-corp", "app-a");
    assertEquals("Bearer old-pat", authHeaderSeen);

    Files.writeString(tokenFile, "rotated-pat\n");
    now[0] += 6_000L;
    serviceWithFile.fetchCredentials("duc-corp", "app-a");

    assertEquals("Bearer rotated-pat", authHeaderSeen);
  }

  @Test
  @DisplayName("an unresolvable token fails explicitly instead of fetching anonymously")
  void fetchCredentials_unresolvableToken_throws(@TempDir Path dir) {
    var serviceWithoutToken = new VaultCredentialService("http://localhost:" + port + "/api",
        new TokenFileSource(dir.resolve("absent").toString(), "", () -> now[0]));
    authHeaderSeen = null;

    assertThrows(VaultCredentialException.class,
        () -> serviceWithoutToken.fetchCredentials("duc-corp", "app-a"));
    assertNull(authHeaderSeen);
  }

  // ─── Slug Validation (path traversal prevention) ─────────────────────────────
  // Only projectId is interpolated into the vault path; orgSlug is unused.

  @Test
  @DisplayName("rejects projectId with path traversal")
  void fetchCredentials_pathTraversal_projectId_throws() {
    assertThrows(VaultCredentialException.class,
        () -> service.fetchCredentials("org", "../../admin"));
  }

  @Test
  @DisplayName("rejects projectName with query string injection")
  void fetchCredentials_queryInjection_projectName_throws() {
    assertThrows(VaultCredentialException.class,
        () -> service.fetchCredentials("org", "app?bypass=1"));
  }

  @Test
  @DisplayName("rejects empty projectId")
  void fetchCredentials_emptyProjectId_throws() {
    assertThrows(VaultCredentialException.class,
        () -> service.fetchCredentials("org", ""));
  }

  @Test
  @DisplayName("rejects null projectName")
  void fetchCredentials_nullProjectName_throws() {
    assertThrows(VaultCredentialException.class,
        () -> service.fetchCredentials("org", null));
  }

  @Test
  @DisplayName("rejects projectId longer than 64 characters")
  void fetchCredentials_tooLongProjectId_throws() {
    String longSlug = "a".repeat(65);
    assertThrows(VaultCredentialException.class,
        () -> service.fetchCredentials("org", longSlug));
  }
}
