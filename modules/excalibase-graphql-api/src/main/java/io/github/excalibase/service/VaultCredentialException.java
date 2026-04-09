package io.github.excalibase.service;

public class VaultCredentialException extends RuntimeException {

  public VaultCredentialException(String message) {
    super(message);
  }

  public VaultCredentialException(String message, Throwable cause) {
    super(message, cause);
  }
}
