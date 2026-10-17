package io.github.excalibase.config.datasource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantTrustStoreTest {

  private static TestPki pki;
  private static TestPki otherPki;

  @TempDir
  Path root;

  @BeforeAll
  static void generatePki(@TempDir Path pkiDir, @TempDir Path otherDir) throws Exception {
    pki = TestPki.generate(pkiDir, "excalibase_app");
    otherPki = TestPki.generate(otherDir, "excalibase_app");
  }

  @Test
  @DisplayName("writes a PKCS12 truststore holding exactly the record's CA, owner-only, in its own directory")
  void writesOwnerOnlyTrustStore() throws Exception {
    try (TenantTrustStore store = TenantTrustStore.write(root, pki.caCertificate)) {
      Path dir = store.file().getParent();
      assertEquals(root, dir.getParent());
      assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())));

      assertEquals(certificates(pki.caCertificate), trusted(store));
    }
  }

  @Test
  @DisplayName("a CA bundle is trusted as a whole: every certificate in it and nothing else")
  void trustsEveryCertificateOfABundle() throws Exception {
    String bundle = pki.caCertificate + otherPki.caCertificate;

    try (TenantTrustStore store = TenantTrustStore.write(root, bundle)) {
      Set<Certificate> trusted = trusted(store);

      assertEquals(2, trusted.size());
      assertEquals(certificates(bundle), trusted);
    }
  }

  @Test
  @DisplayName("each store gets its own directory and its own random password")
  void eachStoreIsIsolated() {
    try (TenantTrustStore first = TenantTrustStore.write(root, pki.caCertificate);
         TenantTrustStore second = TenantTrustStore.write(root, pki.caCertificate)) {
      assertNotEquals(first.file().getParent(), second.file().getParent());
      assertNotEquals(first.password(), second.password());
      assertTrue(first.password().length() >= 32, "password too short");
    }
  }

  @Test
  @DisplayName("close deletes the truststore and its directory")
  void closeDeletesTheDirectory() {
    TenantTrustStore store = TenantTrustStore.write(root, pki.caCertificate);
    Path dir = store.file().getParent();

    store.close();

    assertFalse(Files.exists(dir));
  }

  @Test
  @DisplayName("a missing or blank sslrootcert is refused by name and leaves nothing on disk")
  void refusesMissingRoot() throws IOException {
    for (String absent : new String[] {null, "", "  \n"}) {
      TenantTlsException refused =
          assertThrows(TenantTlsException.class, () -> TenantTrustStore.write(root, absent));
      assertTrue(refused.getMessage().contains("sslrootcert"), refused.getMessage());
    }
    assertDirectoryEmpty();
  }

  @Test
  @DisplayName("an sslrootcert that is not PEM X.509 is refused and leaves nothing on disk")
  void refusesUnparsableRoot() throws IOException {
    String garbage = "-----BEGIN CERTIFICATE-----\nbm90IGEgY2VydA==\n-----END CERTIFICATE-----\n";

    for (String bad : new String[] {"not pem", garbage}) {
      TenantTlsException refused = assertThrows(TenantTlsException.class, () -> TenantTrustStore.write(root, bad));
      assertTrue(refused.getMessage().contains("sslrootcert"), refused.getMessage());
    }
    assertDirectoryEmpty();
  }

  private static Set<Certificate> trusted(TenantTrustStore store) throws IOException, GeneralSecurityException {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(store.file())) {
      keyStore.load(in, store.password().toCharArray());
    }
    Set<Certificate> trusted = new HashSet<>();
    for (String alias : Collections.list(keyStore.aliases())) {
      assertTrue(keyStore.isCertificateEntry(alias), alias + " is not a trusted certificate entry");
      trusted.add(keyStore.getCertificate(alias));
    }
    return trusted;
  }

  private static Set<Certificate> certificates(String pem) throws GeneralSecurityException {
    List<? extends Certificate> parsed = List.copyOf(CertificateFactory.getInstance("X.509")
        .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))));
    return Set.copyOf(parsed);
  }

  private void assertDirectoryEmpty() throws IOException {
    try (Stream<Path> entries = Files.list(root)) {
      assertEquals(0, entries.count());
    }
  }
}
