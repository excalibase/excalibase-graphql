package io.github.excalibase.config.datasource;

import io.github.excalibase.service.VaultCredentialException;

/** A tenant's TLS material (client certificate or server CA) is absent or unusable; its datasource is not built. */
public class TenantTlsException extends VaultCredentialException {

  public TenantTlsException(String message) {
    super(message);
  }

  public TenantTlsException(String message, Throwable cause) {
    super(message, cause);
  }
}
