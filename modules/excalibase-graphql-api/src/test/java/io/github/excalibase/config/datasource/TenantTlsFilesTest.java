package io.github.excalibase.config.datasource;

import io.github.excalibase.service.TenantTlsCredentials;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantTlsFilesTest {

  private static TestPki pki;
  private static TestPki otherPki;

  @TempDir
  Path root;

  @BeforeAll
  static void generatePki(@TempDir Path pkiDir, @TempDir Path otherDir) throws Exception {
    pki = TestPki.generate(pkiDir, "excalibase_app");
    otherPki = TestPki.generate(otherDir, "excalibase_app");
  }

  private static TenantTlsCredentials valid() {
    return new TenantTlsCredentials(pki.clientCertificate, pki.clientKey, pki.caCertificate);
  }

  @Test
  @DisplayName("writes the cert, a PKCS-8 DER key and the root in an owner-only directory")
  void writesOwnerOnlyFiles() throws Exception {
    try (TenantTlsFiles files = TenantTlsFiles.write(root, valid())) {
      Path dir = files.clientCertificate().getParent();
      assertEquals(root, dir.getParent());
      assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
      for (Path file : new Path[] {files.clientCertificate(), files.privateKey(), files.rootCertificate()}) {
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
      }
      assertTrue(files.privateKey().toString().endsWith(".pk8"));
      byte[] der = Files.readAllBytes(files.privateKey());
      assertEquals("EC", KeyFactory.getInstance("EC")
          .generatePrivate(new PKCS8EncodedKeySpec(der)).getAlgorithm());
      assertEquals(pki.clientCertificate, Files.readString(files.clientCertificate()));
      assertEquals(pki.caCertificate, Files.readString(files.rootCertificate()));
    }
  }

  @Test
  @DisplayName("close deletes the material")
  void closeDeletesTheDirectory() throws Exception {
    TenantTlsFiles files = TenantTlsFiles.write(root, valid());
    Path dir = files.clientCertificate().getParent();

    files.close();

    assertFalse(Files.exists(dir));
  }

  @Test
  @DisplayName("each write gets its own directory, so a renewed record never overwrites live files")
  void eachWriteIsIsolated() throws Exception {
    try (TenantTlsFiles first = TenantTlsFiles.write(root, valid());
         TenantTlsFiles second = TenantTlsFiles.write(root, valid())) {
      assertNotEquals(first.clientCertificate().getParent(), second.clientCertificate().getParent());
    }
  }

  @Test
  @DisplayName("a missing field is refused by name")
  void refusesMissingFields() {
    TenantTlsCredentials noKey = new TenantTlsCredentials(pki.clientCertificate, " ", pki.caCertificate);

    TenantTlsException refused = assertThrows(TenantTlsException.class, () -> TenantTlsFiles.write(root, noKey));

    assertTrue(refused.getMessage().contains("sslkey"), refused.getMessage());
    assertDirectoryEmpty();
  }

  @Test
  @DisplayName("unparsable certificate, key or root is refused and leaves nothing on disk")
  void refusesUnparsableMaterial() {
    String garbage = "-----BEGIN CERTIFICATE-----\nbm90IGEgY2VydA==\n-----END CERTIFICATE-----\n";
    String rsaHeader = pki.clientKey.replace("PRIVATE KEY", "RSA PRIVATE KEY");

    assertThrows(TenantTlsException.class, () -> TenantTlsFiles.write(root,
        new TenantTlsCredentials(garbage, pki.clientKey, pki.caCertificate)));
    assertThrows(TenantTlsException.class, () -> TenantTlsFiles.write(root,
        new TenantTlsCredentials(pki.clientCertificate, rsaHeader, pki.caCertificate)));
    assertThrows(TenantTlsException.class, () -> TenantTlsFiles.write(root,
        new TenantTlsCredentials(pki.clientCertificate, pki.clientKey, "not pem")));
    assertDirectoryEmpty();
  }

  @Test
  @DisplayName("a key that does not belong to the certificate is refused")
  void refusesMismatchedKey() {
    TenantTlsCredentials mismatched =
        new TenantTlsCredentials(pki.clientCertificate, otherPki.clientKey, pki.caCertificate);

    TenantTlsException refused =
        assertThrows(TenantTlsException.class, () -> TenantTlsFiles.write(root, mismatched));

    assertTrue(refused.getMessage().contains("does not match"), refused.getMessage());
  }

  @Test
  @DisplayName("error messages never carry key material")
  void errorsDoNotLeakKeys() {
    TenantTlsCredentials mismatched =
        new TenantTlsCredentials(pki.clientCertificate, otherPki.clientKey, "not pem");

    TenantTlsException refused =
        assertThrows(TenantTlsException.class, () -> TenantTlsFiles.write(root, mismatched));

    assertFalse(refused.getMessage().contains("PRIVATE KEY"));
    assertFalse(String.valueOf(refused.getCause()).contains(otherPki.clientKey.substring(30, 60)));
  }

  private void assertDirectoryEmpty() {
    try (Stream<Path> entries = Files.list(root)) {
      assertEquals(0, entries.count());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
