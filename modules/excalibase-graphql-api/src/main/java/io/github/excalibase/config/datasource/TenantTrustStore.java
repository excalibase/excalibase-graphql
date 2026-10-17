package io.github.excalibase.config.datasource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collection;

/**
 * A MySQL tenant pool's server CA(s) as the PKCS12 truststore Connector/J reads (it cannot read PEM):
 * a fresh owner-only directory per pool, removed with it, under a password generated for that pool.
 */
public final class TenantTrustStore implements AutoCloseable {

  static final String TYPE = "PKCS12";
  private static final String FIELD = "sslrootcert";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Path directory;
  private final Path file;
  private final String password;

  private TenantTrustStore(Path directory, String password) {
    this.directory = directory;
    this.file = directory.resolve("truststore.p12");
    this.password = password;
  }

  /** Refuses a blank or unparsable {@code sslrootcert} before anything is written. */
  public static TenantTrustStore write(Path root, String rootCertificates) {
    if (rootCertificates == null || rootCertificates.isBlank()) {
      throw new TenantTlsException("MySQL tenant database record lacks " + FIELD
          + " required to verify the server certificate");
    }
    Collection<X509Certificate> authorities = TenantTlsFiles.parseCertificates(rootCertificates, FIELD);
    String password = randomPassword();
    byte[] keyStore = keyStore(authorities, password.toCharArray());
    TenantTrustStore store = new TenantTrustStore(TenantTlsFiles.createDirectory(root), password);
    try {
      TenantTlsFiles.writeOwnerOnly(store.file, keyStore);
      return store;
    } catch (RuntimeException e) {
      store.close();
      throw e;
    }
  }

  public Path file() {
    return file;
  }

  public String password() {
    return password;
  }

  @Override
  public void close() {
    TenantTlsFiles.deleteRecursively(directory);
  }

  private static byte[] keyStore(Collection<X509Certificate> authorities, char[] password) {
    try {
      KeyStore keyStore = KeyStore.getInstance(TYPE);
      keyStore.load(null, password);
      int index = 0;
      for (X509Certificate authority : authorities) {
        keyStore.setCertificateEntry("server-ca-" + index, authority);
        index++;
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      keyStore.store(out, password);
      return out.toByteArray();
    } catch (GeneralSecurityException | IOException e) {
      throw new TenantTlsException("Cannot build the truststore for " + FIELD, e);
    }
  }

  private static String randomPassword() {
    byte[] secret = new byte[32];
    RANDOM.nextBytes(secret);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
  }
}
