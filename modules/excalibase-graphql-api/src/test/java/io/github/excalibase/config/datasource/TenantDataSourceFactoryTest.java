package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariConfig;
import io.github.excalibase.service.TenantTlsCredentials;
import io.github.excalibase.service.VaultCredentials;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantDataSourceFactoryTest {

  private static TestPki pki;

  @TempDir
  Path root;

  @BeforeAll
  static void generatePki(@TempDir Path pkiDir) throws Exception {
    pki = TestPki.generate(pkiDir, "excalibase_app");
  }

  private static VaultCredentials record(TenantTlsCredentials tls) {
    return new VaultCredentials("db.svc.cluster.local", "5432", "app", "excalibase_app", "pw", tls);
  }

  @Test
  @DisplayName("verify-full refuses a record without client certificate material, before any pool exists")
  void verifyFullRefusesPasswordOnlyRecord() throws IOException {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.VERIFY_FULL, 2, root);

    TenantTlsException refused = assertThrows(TenantTlsException.class, () -> factory.apply(record(null)));

    assertEquals("Tenant database record lacks sslcert, sslkey, sslrootcert required by "
        + "app.tenant-db.sslmode=verify-full", refused.getMessage());
    assertEmpty(root);
  }

  @Test
  @DisplayName("verify-full refuses a record missing only the root certificate")
  void verifyFullRefusesPartialRecord() throws IOException {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.VERIFY_FULL, 2, root);
    TenantTlsCredentials noRoot = new TenantTlsCredentials(pki.clientCertificate, pki.clientKey, null);

    TenantTlsException refused = assertThrows(TenantTlsException.class, () -> factory.apply(record(noRoot)));

    assertEquals("Tenant database record lacks sslrootcert required by app.tenant-db.sslmode=verify-full",
        refused.getMessage());
    assertEmpty(root);
  }

  @Test
  @DisplayName("verify-full points pgjdbc at the written certificate, key and root")
  void verifyFullConnectionProperties() {
    TenantTlsCredentials tls = new TenantTlsCredentials(pki.clientCertificate, pki.clientKey, pki.caCertificate);
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.VERIFY_FULL, 2, root);

    try (TenantTlsFiles files = TenantTlsFiles.write(root, tls)) {
      Properties props = factory.hikariConfig(record(tls), files).getDataSourceProperties();

      assertEquals("verify-full", props.getProperty("sslmode"));
      assertEquals(files.clientCertificate().toString(), props.getProperty("sslcert"));
      assertEquals(files.privateKey().toString(), props.getProperty("sslkey"));
      assertEquals(files.rootCertificate().toString(), props.getProperty("sslrootcert"));
    }
  }

  @Test
  @DisplayName("disable connects with the password as before and needs no certificate")
  void disableUsesPasswordOnly() {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.DISABLE, 2, root);

    HikariConfig config = factory.hikariConfig(record(null), null);

    assertEquals("disable", config.getDataSourceProperties().getProperty("sslmode"));
    assertFalse(config.getDataSourceProperties().containsKey("sslcert"));
    assertEquals("pw", config.getPassword());
    assertEquals("jdbc:postgresql://db.svc.cluster.local:5432/app", config.getJdbcUrl());
  }

  @Test
  @DisplayName("the default factory keeps its material in a private per-process directory it removes on close")
  void privateRootIsRemovedOnClose() {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.VERIFY_FULL, 2);
    Path privateRoot = factory.root();
    assertTrue(Files.isDirectory(privateRoot));

    factory.close();

    assertFalse(Files.exists(privateRoot));
  }

  private static void assertEmpty(Path dir) throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
      assertEquals(0, entries.count());
    }
  }
}
