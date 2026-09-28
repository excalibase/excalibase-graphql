package io.github.excalibase.config.datasource;

/**
 * How the engine connects to tenant databases. {@code disable} exists only for the
 * docker all-in-one, whose tenants have no TLS; every other deployment verifies.
 */
public enum TenantDbSslMode {
  VERIFY_FULL("verify-full"),
  DISABLE("disable");

  private final String jdbcValue;

  TenantDbSslMode(String jdbcValue) {
    this.jdbcValue = jdbcValue;
  }

  public String jdbcValue() {
    return jdbcValue;
  }

  public static TenantDbSslMode parse(String value) {
    for (TenantDbSslMode mode : values()) {
      if (mode.jdbcValue.equals(value)) {
        return mode;
      }
    }
    throw new IllegalStateException(
        "app.tenant-db.sslmode (TENANT_DB_SSLMODE) must be verify-full or disable, got: " + value);
  }
}
