package io.github.excalibase.config.datasource;

import io.github.excalibase.service.VaultCredentialException;

/** A tenant's client certificate material is absent or unusable; its datasource is not built. */
public class TenantTlsException extends VaultCredentialException {

  public TenantTlsException(String message) {
    super(message);
  }

  public TenantTlsException(String message, Throwable cause) {
    super(message, cause);
  }
}
