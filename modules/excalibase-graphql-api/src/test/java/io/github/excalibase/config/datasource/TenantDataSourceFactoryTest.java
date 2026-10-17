package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariConfig;
import io.github.excalibase.service.DatabaseEngine;
import io.github.excalibase.service.TenantTlsCredentials;
import io.github.excalibase.service.VaultCredentials;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

  private static VaultCredentials vaultRecord(TenantTlsCredentials tls) {
    return new VaultCredentials("db.svc.cluster.local", "5432", "app", "excalibase_app", "pw", tls);
  }

  private static VaultCredentials mysqlRecord(String serverCa) {
    return new VaultCredentials("db.svc.cluster.local", "3306", "app", "excalibase_app", "pw",
        null, DatabaseEngine.MYSQL, serverCa);
  }

  @Test
  @DisplayName("verify-full refuses a record without client certificate material, before any pool exists")
  void verifyFullRefusesPasswordOnlyRecord() throws IOException {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.VERIFY_FULL, 2, root);

    VaultCredentials passwordOnly = vaultRecord(null);

    TenantTlsException refused = assertThrows(TenantTlsException.class, () -> factory.apply(passwordOnly));

    assertEquals("Tenant database record lacks sslcert, sslkey, sslrootcert required by "
        + "app.tenant-db.sslmode=verify-full", refused.getMessage());
    assertEmpty(root);
  }

  @Test
  @DisplayName("verify-full refuses a record missing only the root certificate")
  void verifyFullRefusesPartialRecord() throws IOException {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.VERIFY_FULL, 2, root);
    TenantTlsCredentials noRoot = new TenantTlsCredentials(pki.clientCertificate, pki.clientKey, null);
    VaultCredentials partial = vaultRecord(noRoot);

    TenantTlsException refused = assertThrows(TenantTlsException.class, () -> factory.apply(partial));

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
      Properties props = factory.postgresConfig(vaultRecord(tls), files).getDataSourceProperties();

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

    HikariConfig config = factory.postgresConfig(vaultRecord(null), null);

    assertEquals("disable", config.getDataSourceProperties().getProperty("sslmode"));
    assertFalse(config.getDataSourceProperties().containsKey("sslcert"));
    assertEquals("pw", config.getPassword());
    assertEquals("jdbc:postgresql://db.svc.cluster.local:5432/app", config.getJdbcUrl());
  }

  @ParameterizedTest
  @EnumSource(TenantDbSslMode.class)
  @DisplayName("a MySQL pool verifies the server against the record's CA alone, whatever the Postgres sslmode")
  void mysqlVerifiesServerAgainstRecordCa(TenantDbSslMode sslMode) {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(sslMode, 2, root);

    try (TenantTrustStore trustStore = TenantTrustStore.write(root, pki.caCertificate)) {
      HikariConfig config = factory.mysqlConfig(mysqlRecord(pki.caCertificate), trustStore);
      Properties props = config.getDataSourceProperties();

      assertEquals("jdbc:mysql://db.svc.cluster.local:3306/app", config.getJdbcUrl());
      assertEquals("VERIFY_IDENTITY", props.getProperty("sslMode"));
      assertEquals(trustStore.file().toUri().toString(), props.getProperty("trustCertificateKeyStoreUrl"));
      assertEquals("PKCS12", props.getProperty("trustCertificateKeyStoreType"));
      assertEquals(trustStore.password(), props.getProperty("trustCertificateKeyStorePassword"));
      assertEquals("false", props.getProperty("fallbackToSystemTrustStore"));
      assertFalse(props.containsKey("sslmode"));
      assertFalse(props.containsKey("sslcert"));
      assertEquals("pw", config.getPassword());
      assertEquals("SET time_zone = '+00:00'", config.getConnectionInitSql());
    }
  }

  @ParameterizedTest
  @EnumSource(TenantDbSslMode.class)
  @DisplayName("a MySQL record without a usable sslrootcert gets no pool, whatever the Postgres sslmode")
  void mysqlRecordWithoutRootIsRefused(TenantDbSslMode sslMode) throws IOException {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(sslMode, 2, root);
    String garbage = "-----BEGIN CERTIFICATE-----\nbm90IGEgY2VydA==\n-----END CERTIFICATE-----\n";

    for (String unusable : new String[] {null, " ", "not pem", garbage}) {
      VaultCredentials record = mysqlRecord(unusable);
      TenantTlsException refused = assertThrows(TenantTlsException.class, () -> factory.apply(record));
      assertTrue(refused.getMessage().contains("sslrootcert"), refused.getMessage());
    }
    assertEmpty(root);
  }

  @Test
  @DisplayName("verify-full asks a MySQL record for no client certificate: MySQL logins use passwords over TLS")
  void verifyFullMysqlRecordNeedsNoCertificate() {
    TenantDataSourceFactory factory = new TenantDataSourceFactory(TenantDbSslMode.VERIFY_FULL, 2, root);

    assertFalse(factory.needsCertificates(mysqlRecord(pki.caCertificate)));
    assertTrue(factory.needsCertificates(vaultRecord(null)));
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
