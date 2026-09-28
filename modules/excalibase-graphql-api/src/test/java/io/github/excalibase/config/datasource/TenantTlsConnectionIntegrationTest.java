package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;
import io.github.excalibase.service.TenantTlsCredentials;
import io.github.excalibase.service.VaultCredentials;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant Postgres shaped like the CNPG one after EXC-410: TLS on and the platform
 * role cert-only in pg_hba. The engine must get in with the record's client
 * certificate and nothing else.
 */
class TenantTlsConnectionIntegrationTest {

  private static final String ROLE = "excalibase_app";
  private static final String PG_HBA = """
      local all all trust
      hostssl all excalibase_app all cert
      host all excalibase_app all reject
      host all all all scram-sha-256
      """;
  // Postgres refuses a key readable by others, and copied files are root-owned 0644.
  private static final String PREPARE_TLS = "mkdir -p /tls && cp /tls-src/* /tls/ && chown postgres /tls/* "
      + "&& chmod 600 /tls/* && exec docker-entrypoint.sh \"$0\" \"$@\"";

  private static TestPki pki;
  private static TestPki foreignPki;
  private static PostgreSQLContainer<?> postgres;

  @TempDir
  Path root;

  @BeforeAll
  static void startTlsPostgres(@TempDir Path pkiDir, @TempDir Path foreignDir) throws Exception {
    pki = TestPki.generate(pkiDir, ROLE);
    foreignPki = TestPki.generate(foreignDir, ROLE);
    postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withCopyToContainer(Transferable.of(pki.serverCertificate), "/tls-src/server.crt")
        .withCopyToContainer(Transferable.of(pki.serverKey), "/tls-src/server.key")
        .withCopyToContainer(Transferable.of(pki.caCertificate), "/tls-src/ca.crt")
        .withCopyToContainer(Transferable.of(PG_HBA), "/tls-src/pg_hba.conf")
        .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c", PREPARE_TLS))
        .withCommand("postgres", "-c", "fsync=off", "-c", "ssl=on",
            "-c", "ssl_cert_file=/tls/server.crt", "-c", "ssl_key_file=/tls/server.key",
            "-c", "ssl_ca_file=/tls/ca.crt", "-c", "hba_file=/tls/pg_hba.conf");
    postgres.start();
    try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(),
        postgres.getUsername(), postgres.getPassword());
         Statement statement = admin.createStatement()) {
      statement.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD 'password-login'");
    }
  }

  @AfterAll
  static void stopPostgres() {
    if (postgres != null) {
      postgres.stop();
    }
  }

  private static VaultCredentials vaultRecord(TenantTlsCredentials tls) {
    return new VaultCredentials(postgres.getHost(), String.valueOf(postgres.getFirstMappedPort()),
        postgres.getDatabaseName(), ROLE, "not-the-password", tls);
  }

  private static TenantTlsCredentials issued(TestPki client, String rootCertificate) {
    return new TenantTlsCredentials(client.clientCertificate, client.clientKey, rootCertificate);
  }

  @Test
  @DisplayName("connects as the platform role with the EC client certificate over verified TLS")
  void connectsWithClientCertificate() throws Exception {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.VERIFY_FULL, 2, root);
    DataSource dataSource = factory.apply(vaultRecord(issued(pki, pki.caCertificate)));

    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet row = statement.executeQuery("SELECT current_user, ssl FROM pg_stat_ssl "
             + "WHERE pid = pg_backend_pid()")) {
      assertTrue(row.next());
      assertEquals(ROLE, row.getString(1));
      assertTrue(row.getBoolean(2));
    } finally {
      ((AutoCloseable) dataSource).close();
    }
    assertEmpty(root);
  }

  @Test
  @DisplayName("a record without a client certificate is refused, and the server itself refuses the password")
  void passwordOnlyIsRefused() {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.VERIFY_FULL, 2, root);

    VaultCredentials withoutCertificate = vaultRecord(null);
    assertThrows(TenantTlsException.class, () -> factory.apply(withoutCertificate));

    Properties passwordOnly = new Properties();
    passwordOnly.setProperty("user", ROLE);
    passwordOnly.setProperty("password", "password-login");
    passwordOnly.setProperty("sslmode", "require");
    String url = postgres.getJdbcUrl();
    assertThrows(SQLException.class, () -> DriverManager.getConnection(url, passwordOnly));
  }

  @Test
  @DisplayName("a server whose certificate the record's CA did not sign is refused, never downgraded")
  void untrustedServerIsRefused() throws Exception {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.VERIFY_FULL, 2, root);

    VaultCredentials foreignRoot = vaultRecord(issued(pki, foreignPki.caCertificate));

    assertThrows(PoolInitializationException.class, () -> factory.apply(foreignRoot));
    assertEmpty(root);
  }

  @Test
  @DisplayName("a client certificate from another CA is refused by the server")
  void foreignClientCertificateIsRefused() throws Exception {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.VERIFY_FULL, 2, root);

    VaultCredentials foreignClient = vaultRecord(issued(foreignPki, pki.caCertificate));

    assertThrows(PoolInitializationException.class, () -> factory.apply(foreignClient));
    assertEmpty(root);
  }

  private static void assertEmpty(Path dir) throws Exception {
    try (Stream<Path> entries = Files.list(dir)) {
      assertFalse(entries.findAny().isPresent());
    }
  }
}
