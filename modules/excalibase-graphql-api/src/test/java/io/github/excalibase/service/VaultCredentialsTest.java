package io.github.excalibase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VaultCredentialsTest {

  @Test
  @DisplayName("jdbcUrl builds valid URL from safe values")
  void jdbcUrl_validValues_buildsUrl() {
    var creds = new VaultCredentials("db.svc.local", "5432", "mydb", "user", "pass");
    assertEquals("jdbc:postgresql://db.svc.local:5432/mydb", creds.jdbcUrl());
  }

  @Test
  @DisplayName("a MySQL record builds a Connector/J URL with nothing but host, port and database")
  void jdbcUrl_mysql_buildsConnectorJUrl() {
    var creds = new VaultCredentials("db.svc.local", "3306", "mydb", "user", "pass", null, DatabaseEngine.MYSQL);
    assertEquals("jdbc:mysql://db.svc.local:3306/mydb", creds.jdbcUrl());
  }

  @Test
  @DisplayName("a MySQL record is validated like a Postgres one")
  void jdbcUrl_mysqlHostWithQueryString_throws() {
    var creds = new VaultCredentials("evil?allowLoadLocalInfile=true", "3306", "db", "u", "p", null,
        DatabaseEngine.MYSQL);
    assertThrows(IllegalArgumentException.class, creds::jdbcUrl);
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

  @Test
  @DisplayName("toString never prints the private key or certificates")
  void toString_redactsKeyMaterial() {
    var tls = new TenantTlsCredentials("CERT-PEM", "KEY-PEM", "ROOT-PEM");
    var creds = new VaultCredentials("host", "5432", "db", "user", "secret123", tls);

    String str = creds.toString();

    assertFalse(str.contains("KEY-PEM"));
    assertFalse(str.contains("CERT-PEM"));
    assertFalse(tls.toString().contains("KEY-PEM"));
  }

  @Test
  @DisplayName("toString never prints the MySQL server CA")
  void toString_redactsServerCa() {
    var creds = new VaultCredentials("host", "3306", "db", "user", "secret123", null, DatabaseEngine.MYSQL,
        "CA-PEM");

    String str = creds.toString();

    assertFalse(str.contains("CA-PEM"));
    assertTrue(str.contains("serverCa=REDACTED"), str);
  }

  @Test
  @DisplayName("missingFields names each blank certificate field")
  void missingFields_namesBlankFields() {
    assertEquals(List.of(), new TenantTlsCredentials("c", "k", "r").missingFields());
    assertEquals(List.of("sslcert", "sslrootcert"), new TenantTlsCredentials(" ", "k", null).missingFields());
  }
}
