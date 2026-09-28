package io.github.excalibase.config.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantDbSslModeTest {

  @Test
  @DisplayName("verify-full and disable are the only accepted modes")
  void parsesTheTwoAcceptedModes() {
    assertEquals(TenantDbSslMode.VERIFY_FULL, TenantDbSslMode.parse("verify-full"));
    assertEquals(TenantDbSslMode.DISABLE, TenantDbSslMode.parse("disable"));
    assertEquals("verify-full", TenantDbSslMode.VERIFY_FULL.jdbcValue());
    assertEquals("disable", TenantDbSslMode.DISABLE.jdbcValue());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"require", "prefer", "allow", "verify-ca", "VERIFY_FULL", " disable"})
  @DisplayName("any other value, weaker TLS modes included, is refused")
  void refusesEverythingElse(String value) {
    IllegalStateException refused = assertThrows(IllegalStateException.class,
        () -> TenantDbSslMode.parse(value));
    assertEquals("app.tenant-db.sslmode (TENANT_DB_SSLMODE) must be verify-full or disable, got: " + value,
        refused.getMessage());
  }
}
