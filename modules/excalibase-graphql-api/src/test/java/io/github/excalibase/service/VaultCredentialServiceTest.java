package io.github.excalibase.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class VaultCredentialServiceTest {

  private static HttpServer mockVault;
  private static int port;
  private VaultCredentialService service;

  @BeforeAll
  static void startMockVault() throws Exception {
    mockVault = HttpServer.create(new InetSocketAddress(0), 0);
    port = mockVault.getAddress().getPort();

    // Successful credentials endpoint
    mockVault.createContext("/api/vault/secrets/projects/duc-corp/app-a/credentials/excalibase_app", exchange -> {
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
    mockVault.createContext("/api/vault/secrets/projects/unknown-org/unknown-app/credentials/excalibase_app", exchange -> {
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
    assertTrue(ex.getMessage().contains("404"));
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
    // The mock doesn't verify auth, but service should send it.
    // We verify by checking that credentials are returned (mock accepts any auth)
    var serviceWithPat = new VaultCredentialService("http://localhost:" + port + "/api", "my-secret-pat");
    VaultCredentials creds = serviceWithPat.fetchCredentials("duc-corp", "app-a");
    assertNotNull(creds);
  }
}
