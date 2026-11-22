package io.github.excalibase.config.datasource;

import io.github.excalibase.service.VaultCredentialException;

/**
 * The project's database refused the pool's first connection: it is down, still starting, or shut down by a pause
 * that provisioning did not report. The message names no host; the driver's own is in the cause, for the log.
 */
public class TenantDatabaseUnreachableException extends VaultCredentialException {

  public TenantDatabaseUnreachableException(String projectId, Throwable cause) {
    super("The database of project " + projectId + " could not be reached", cause);
  }
}
