package io.github.excalibase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VaultCredentialsTest {

  @Test
  @DisplayName("jdbcUrl builds valid URL from safe values")
  void jdbcUrl_validValues_buildsUrl() {
    var creds = new VaultCredentials("db.svc.local", "5432", "mydb", "user", "pass");
    assertEquals("jdbc:postgresql://db.svc.local:5432/mydb", creds.jdbcUrl());
  }

  @Test
  @DisplayName("jdbcUrl rejects host with query string injection")
  void jdbcUrl_hostWithQueryString_throws() {
    var creds = new VaultCredentials("evil.host?socketFactory=bad", "5432", "db", "u", "p");
    assertThrows(IllegalArgumentException.class, creds::jdbcUrl);
  }

  @Test
  @DisplayName("jdbcUrl rejects host with ampersand")
  void jdbcUrl_hostWithAmpersand_throws() {
    var creds = new VaultCredentials("host&param=val", "5432", "db", "u", "p");
    assertThrows(IllegalArgumentException.class, creds::jdbcUrl);
  }

  @Test
  @DisplayName("jdbcUrl rejects non-numeric port")
  void jdbcUrl_nonNumericPort_throws() {
    var creds = new VaultCredentials("host", "abc", "db", "u", "p");
    assertThrows(IllegalArgumentException.class, creds::jdbcUrl);
  }

  @Test
  @DisplayName("jdbcUrl rejects port out of range")
  void jdbcUrl_portOutOfRange_throws() {
    var creds = new VaultCredentials("host", "99999", "db", "u", "p");
    assertThrows(IllegalArgumentException.class, creds::jdbcUrl);
  }

  @Test
  @DisplayName("jdbcUrl rejects database with special characters")
  void jdbcUrl_databaseWithSpecialChars_throws() {
    var creds = new VaultCredentials("host", "5432", "db?autosave=always", "u", "p");
    assertThrows(IllegalArgumentException.class, creds::jdbcUrl);
  }

  @Test
  @DisplayName("toString redacts password")
  void toString_redactsPassword() {
    var creds = new VaultCredentials("host", "5432", "db", "user", "secret123");
    String str = creds.toString();
    assertFalse(str.contains("secret123"));
    assertTrue(str.contains("REDACTED"));
  }
}
