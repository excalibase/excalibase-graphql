package io.github.excalibase.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Client certificate material for a platform role, as provisioning stores it in the
 * vault record: PEM certificate (CN = role), PEM PKCS#8 key, PEM CA of the server.
 */
public record TenantTlsCredentials(String clientCertificate, String privateKey, String rootCertificate) {

  private static final int FIELDS = 3;

  /** Vault field names of the blank or absent parts, in record order. */
  public List<String> missingFields() {
    List<String> missing = new ArrayList<>();
    addIfBlank(missing, "sslcert", clientCertificate);
    addIfBlank(missing, "sslkey", privateKey);
    addIfBlank(missing, "sslrootcert", rootCertificate);
    return List.copyOf(missing);
  }

  /** True when none of the three fields is present: a record for a tenant without TLS. */
  public boolean isAbsent() {
    return missingFields().size() == FIELDS;
  }

  private static void addIfBlank(List<String> missing, String field, String value) {
    if (value == null || value.isBlank()) {
      missing.add(field);
    }
  }

  @Override
  public String toString() {
    return "TenantTlsCredentials[REDACTED]";
  }
}
