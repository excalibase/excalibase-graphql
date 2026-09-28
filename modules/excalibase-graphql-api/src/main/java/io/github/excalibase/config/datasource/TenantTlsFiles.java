package io.github.excalibase.config.datasource;

import io.github.excalibase.service.TenantTlsCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One tenant pool's client certificate, key and server CA, validated and written
 * where pgjdbc reads them: a fresh owner-only directory per pool, removed with it.
 * pgjdbc takes the key as PKCS-8 DER ({@code .pk8}), so the PEM body is decoded.
 */
public final class TenantTlsFiles implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(TenantTlsFiles.class);
  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIR =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_FILE =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
  private static final String KEY_HEADER = "-----BEGIN PRIVATE KEY-----";
  private static final String KEY_FOOTER = "-----END PRIVATE KEY-----";
  private static final Map<String, String> PROOF_SIGNATURES =
      Map.of("EC", "SHA256withECDSA", "RSA", "SHA256withRSA");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Path directory;
  private final Path clientCertificate;
  private final Path privateKey;
  private final Path rootCertificate;

  private TenantTlsFiles(Path directory) {
    this.directory = directory;
    this.clientCertificate = directory.resolve("client.crt");
    this.privateKey = directory.resolve("client.pk8");
    this.rootCertificate = directory.resolve("root.crt");
  }

  /** Refuses a record that lacks any of the three fields; {@code tls} may be null. */
  public static void requireComplete(TenantTlsCredentials tls) {
    List<String> missing = tls == null
        ? List.of("sslcert", "sslkey", "sslrootcert")
        : tls.missingFields();
    if (!missing.isEmpty()) {
      throw new TenantTlsException("Tenant database record lacks " + String.join(", ", missing)
          + " required by app.tenant-db.sslmode=verify-full");
    }
  }

  public static TenantTlsFiles write(Path root, TenantTlsCredentials tls) {
    requireComplete(tls);
    X509Certificate certificate = parseClientCertificate(tls.clientCertificate());
    byte[] keyDer = parsePrivateKey(tls.privateKey(), certificate.getPublicKey());
    parseRootCertificates(tls.rootCertificate());
    TenantTlsFiles files = new TenantTlsFiles(createDirectory(root));
    try {
      writeOwnerOnly(files.clientCertificate, tls.clientCertificate().getBytes(StandardCharsets.UTF_8));
      writeOwnerOnly(files.privateKey, keyDer);
      writeOwnerOnly(files.rootCertificate, tls.rootCertificate().getBytes(StandardCharsets.UTF_8));
      return files;
    } catch (RuntimeException e) {
      files.close();
      throw e;
    }
  }

  public Path clientCertificate() {
    return clientCertificate;
  }

  public Path privateKey() {
    return privateKey;
  }

  public Path rootCertificate() {
    return rootCertificate;
  }

  @Override
  public void close() {
    deleteRecursively(directory);
  }

  static void deleteRecursively(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (Stream<Path> entries = Files.walk(path)) {
      for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(entry);
      }
    } catch (IOException e) {
      log.warn("tenant_tls_cleanup_failed path={}", path, e);
    }
  }

  private static X509Certificate parseClientCertificate(String pem) {
    Collection<X509Certificate> certificates = parseCertificates(pem, "sslcert");
    return certificates.iterator().next();
  }

  private static void parseRootCertificates(String pem) {
    parseCertificates(pem, "sslrootcert");
  }

  @SuppressWarnings("unchecked")
  private static Collection<X509Certificate> parseCertificates(String pem, String field) {
    try {
      Collection<X509Certificate> certificates = (Collection<X509Certificate>) CertificateFactory
          .getInstance("X.509")
          .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
      if (certificates.isEmpty()) {
        throw new TenantTlsException(field + " holds no PEM X.509 certificate");
      }
      return certificates;
    } catch (CertificateException e) {
      throw new TenantTlsException(field + " is not a PEM X.509 certificate", e);
    }
  }

  /** Returns the PKCS-8 DER bytes once they decode as a key that belongs to the certificate. */
  private static byte[] parsePrivateKey(String pem, PublicKey certificateKey) {
    String trimmed = pem.strip();
    if (!trimmed.startsWith(KEY_HEADER) || !trimmed.endsWith(KEY_FOOTER)) {
      throw new TenantTlsException("sslkey must be an unencrypted PKCS#8 PEM key (" + KEY_HEADER + ")");
    }
    String body = trimmed.substring(KEY_HEADER.length(), trimmed.length() - KEY_FOOTER.length());
    byte[] der;
    PrivateKey key;
    try {
      der = Base64.getMimeDecoder().decode(body);
      key = KeyFactory.getInstance(certificateKey.getAlgorithm()).generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      // The cause is dropped on purpose: parser messages can echo key bytes.
      throw new TenantTlsException("sslkey is not a readable " + certificateKey.getAlgorithm() + " private key");
    }
    requireMatchingPair(key, certificateKey);
    return der;
  }

  private static void requireMatchingPair(PrivateKey key, PublicKey certificateKey) {
    String algorithm = PROOF_SIGNATURES.get(certificateKey.getAlgorithm());
    if (algorithm == null) {
      throw new TenantTlsException("sslcert uses unsupported key algorithm " + certificateKey.getAlgorithm());
    }
    byte[] challenge = new byte[32];
    RANDOM.nextBytes(challenge);
    try {
      Signature signer = Signature.getInstance(algorithm);
      signer.initSign(key);
      signer.update(challenge);
      byte[] signature = signer.sign();
      Signature verifier = Signature.getInstance(algorithm);
      verifier.initVerify(certificateKey);
      verifier.update(challenge);
      if (!verifier.verify(signature)) {
        throw new TenantTlsException("sslkey does not match the public key of sslcert");
      }
    } catch (GeneralSecurityException e) {
      throw new TenantTlsException("sslkey does not match the public key of sslcert", e);
    }
  }

  private static Path createDirectory(Path root) {
    try {
      return Files.createTempDirectory(root, "tenant-", OWNER_ONLY_DIR);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create tenant TLS directory under " + root, e);
    }
  }

  private static void writeOwnerOnly(Path file, byte[] content) {
    try {
      Files.write(Files.createFile(file, OWNER_ONLY_FILE), content);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write tenant TLS file " + file.getFileName(), e);
    }
  }
}
