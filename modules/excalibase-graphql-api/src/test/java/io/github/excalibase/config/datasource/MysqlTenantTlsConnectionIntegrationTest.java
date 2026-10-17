package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;
import io.github.excalibase.service.DatabaseEngine;
import io.github.excalibase.service.VaultCredentials;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.images.builder.Transferable;

import javax.net.ssl.SSLHandshakeException;
import javax.sql.DataSource;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A tenant MySQL shaped like the MOCO one after EXC-589: its server certificate is issued by the
 * project's CA and the platform login is REQUIRE SSL. The engine must reach it only by verifying
 * that certificate against the record's CA and host.
 */
class MysqlTenantTlsConnectionIntegrationTest {

  private static final String ROLE = "excalibase_app";
  private static final String PASSWORD = "role-password";

  private static TestPki pki;
  private static TestPki foreignPki;
  private static MySQLContainer<?> mysql;

  @TempDir
  Path root;

  @BeforeAll
  static void startTlsMysql(@TempDir Path pkiDir, @TempDir Path foreignDir) throws Exception {
    // SAN names only localhost, so the loopback IP reaches the same server under a name it was not issued for.
    pki = TestPki.generate(pkiDir, ROLE, "DNS:localhost");
    foreignPki = TestPki.generate(foreignDir, ROLE, "DNS:localhost");
    mysql = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("app")
        .withCopyToContainer(Transferable.of(pki.serverCertificate), "/tls/server.crt")
        .withCopyToContainer(Transferable.of(pki.serverKey), "/tls/server.key")
        .withCommand("--ssl-cert=/tls/server.crt", "--ssl-key=/tls/server.key");
    mysql.start();
    try (Connection admin = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
         Statement statement = admin.createStatement()) {
      statement.execute("CREATE USER '" + ROLE + "'@'%' IDENTIFIED BY '" + PASSWORD + "' REQUIRE SSL");
      statement.execute("GRANT SELECT ON app.* TO '" + ROLE + "'@'%'");
    }
  }

  @AfterAll
  static void stopMysql() {
    if (mysql != null) {
      mysql.stop();
    }
  }

  private static VaultCredentials vaultRecord(String host, String serverCa) {
    return new VaultCredentials(host, String.valueOf(mysql.getFirstMappedPort()), "app", ROLE, PASSWORD,
        null, DatabaseEngine.MYSQL, serverCa);
  }

  @Test
  @DisplayName("connects over TLS when the record's CA signed the server certificate issued for its host")
  void connectsToVerifiedServer() throws Exception {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.DISABLE, 2, root);
    DataSource dataSource = factory.apply(vaultRecord(mysql.getHost(), pki.caCertificate));

    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet row = statement.executeQuery("SELECT CURRENT_USER(), "
             + "(SELECT VARIABLE_VALUE FROM performance_schema.session_status WHERE VARIABLE_NAME = 'Ssl_cipher')")) {
      assertTrue(row.next());
      assertEquals(ROLE + "@%", row.getString(1));
      assertFalse(row.getString(2).isBlank(), "session is not encrypted");
    } finally {
      ((AutoCloseable) dataSource).close();
    }
    assertEmpty(root);
  }

  @Test
  @DisplayName("a CA bundle that includes the server's CA is trusted")
  void connectsWithCaBundle() throws Exception {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.VERIFY_FULL, 2, root);
    String bundle = foreignPki.caCertificate + pki.caCertificate;
    DataSource dataSource = factory.apply(vaultRecord(mysql.getHost(), bundle));

    try (Connection connection = dataSource.getConnection()) {
      assertTrue(connection.isValid(2));
    } finally {
      ((AutoCloseable) dataSource).close();
    }
    assertEmpty(root);
  }

  @Test
  @DisplayName("a server whose certificate the record's CA did not sign is refused, never downgraded")
  void untrustedServerIsRefused() throws Exception {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.DISABLE, 2, root);

    VaultCredentials foreignCa = vaultRecord(mysql.getHost(), foreignPki.caCertificate);

    assertRefusedBy(SSLHandshakeException.class,
        assertThrows(PoolInitializationException.class, () -> factory.apply(foreignCa)));
    assertEmpty(root);
  }

  @Test
  @DisplayName("a host the server certificate was not issued for is refused even with the right CA")
  void hostOutsideTheCertificateIsRefused() throws Exception {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.DISABLE, 2, root);
    String address = InetAddress.getByName(mysql.getHost()).getHostAddress();

    VaultCredentials byAddress = vaultRecord(address, pki.caCertificate);

    // Connector/J checks the host after the handshake, as a CertificateException of its own.
    assertRefusedBy(CertificateException.class,
        assertThrows(PoolInitializationException.class, () -> factory.apply(byAddress)));
    assertEmpty(root);
  }

  @Test
  @DisplayName("a record without a CA gets no pool, so the server is never reached unverified")
  void recordWithoutCaIsRefused() throws Exception {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.DISABLE, 2, root);

    VaultCredentials withoutCa = vaultRecord(mysql.getHost(), null);

    assertThrows(TenantTlsException.class, () -> factory.apply(withoutCa));
    assertEmpty(root);
  }

  private static void assertRefusedBy(Class<? extends Throwable> expected, Throwable refused) {
    for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
      if (expected.isInstance(cause)) {
        return;
      }
    }
    fail("expected the connection to be refused by " + expected.getSimpleName(), refused);
  }

  private static void assertEmpty(Path dir) throws Exception {
    try (Stream<Path> entries = Files.list(dir)) {
      assertFalse(entries.findAny().isPresent());
    }
  }
}
