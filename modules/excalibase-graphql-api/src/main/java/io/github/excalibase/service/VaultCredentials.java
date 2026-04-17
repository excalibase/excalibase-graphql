package io.github.excalibase.service;

import java.util.regex.Pattern;

public record VaultCredentials(String host, String port, String database,
                               String username, String password) {

  private static final Pattern HOST_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{1,253}$");
  private static final Pattern DB_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,63}$");

  public String jdbcUrl() {
    if (!HOST_PATTERN.matcher(host).matches()) {
      throw new IllegalArgumentException("Invalid host from vault: contains disallowed characters");
    }
    int portNum;
    try {
      portNum = Integer.parseInt(port);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid port from vault: not numeric");
    }
    if (portNum < 1 || portNum > 65535) {
      throw new IllegalArgumentException("Invalid port from vault: out of range");
    }
    if (!DB_PATTERN.matcher(database).matches()) {
      throw new IllegalArgumentException("Invalid database from vault: contains disallowed characters");
    }
    return "jdbc:postgresql://" + host + ":" + port + "/" + database;
  }

  @Override
  public String toString() {
    // NOSONAR: literal "password" is a redaction label, not a hard-coded secret
    return "VaultCredentials[host=" + host + ", port=" + port +
        ", database=" + database + ", username=" + username + ", password=REDACTED]"; // NOSONAR
  }
}
