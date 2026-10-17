package io.github.excalibase.service;

import java.util.regex.Pattern;

/**
 * {@code tls} is a Postgres record's client certificate, null when it carries none. {@code serverCa} is a
 * MySQL record's {@code sslrootcert}: the PEM CA(s) its server certificate must chain to, null when absent.
 */
public record VaultCredentials(String host, String port, String database,
                               String username, String password, TenantTlsCredentials tls,
                               DatabaseEngine engine, String serverCa) {

  private static final Pattern HOST_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{1,253}$");
  private static final Pattern DB_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,63}$");

  public VaultCredentials(String host, String port, String database, String username, String password) {
    this(host, port, database, username, password, null);
  }

  public VaultCredentials(String host, String port, String database, String username, String password,
                          TenantTlsCredentials tls) {
    this(host, port, database, username, password, tls, DatabaseEngine.POSTGRES);
  }

  public VaultCredentials(String host, String port, String database, String username, String password,
                          TenantTlsCredentials tls, DatabaseEngine engine) {
    this(host, port, database, username, password, tls, engine, null);
  }

  public String jdbcUrl() {
    if (!HOST_PATTERN.matcher(host).matches()) {
      throw new IllegalArgumentException("Invalid host from vault: contains disallowed characters");
    }
    int portNum;
    try {
      portNum = Integer.parseInt(port);
    } catch (NumberFormatException _) {
      throw new IllegalArgumentException("Invalid port from vault: not numeric");
    }
    if (portNum < 1 || portNum > 65535) {
      throw new IllegalArgumentException("Invalid port from vault: out of range");
    }
    if (!DB_PATTERN.matcher(database).matches()) {
      throw new IllegalArgumentException("Invalid database from vault: contains disallowed characters");
    }
    return engine.jdbcPrefix() + host + ":" + port + "/" + database;
  }

  @Override
  public String toString() {
    // NOSONAR: literal "password" is a redaction label, not a hard-coded secret
    return "VaultCredentials[engine=" + engine + ", host=" + host + ", port=" + port +
        ", database=" + database + ", username=" + username + ", password=REDACTED, tls=" + redacted(tls) + ", serverCa=" + redacted(serverCa) + "]"; // NOSONAR
  }

  private static String redacted(Object material) {
    return material == null ? "none" : "REDACTED";
  }
}
