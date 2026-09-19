package io.github.excalibase.service;

import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.security.TokenFileSource;
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

    mockVault.start();
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
  @DisplayName("fetchCredentials builds correct JDBC URL")
  void fetchCredentials_buildsJdbcUrl() {
    VaultCredentials creds = service.fetchCredentials("duc-corp", "app-a");
    assertEquals("jdbc:postgresql://app-a-postgres-rw.svc.local:5432/app", creds.jdbcUrl());
  }

  @Test
  @DisplayName("fetchCredentials throws on non-200 response")
  void fetchCredentials_notFound_throws() {
    var ex = assertThrows(VaultCredentialException.class,
        () -> service.fetchCredentials("unknown-org", "unknown-app"));
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
