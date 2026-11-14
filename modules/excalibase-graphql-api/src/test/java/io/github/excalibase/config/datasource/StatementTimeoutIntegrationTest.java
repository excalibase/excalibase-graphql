package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.excalibase.service.DatabaseEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EXC-711: the database cancels a slow statement itself, a little before the socket timeout gives up. */
class StatementTimeoutIntegrationTest {

  private static final Duration READ_TIMEOUT = Duration.ofSeconds(4);
  private static final long STATEMENT_MILLIS = 3_600;

  private static PostgreSQLContainer<?> postgres;
  private static MySQLContainer<?> mysql;

  @BeforeAll
  static void start() {
    postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    mysql = new MySQLContainer<>("mysql:8.4");
    postgres.start();
    mysql.start();
  }

  @AfterAll
  static void stop() {
    if (postgres != null) postgres.stop();
    if (mysql != null) mysql.stop();
  }

  private static HikariDataSource pool(String url, String user, String password, DatabaseEngine engine) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(1);
    ConnectionBounds.apply(config, engine, READ_TIMEOUT);
    return new HikariDataSource(config);
  }

  @Test
  @DisplayName("the timeout sits slightly under the socket timeout")
  void timeoutIsBelowTheSocketTimeout() {
    assertEquals(STATEMENT_MILLIS, ConnectionBounds.statementTimeoutMillis(READ_TIMEOUT));
    assertEquals(900, ConnectionBounds.statementTimeoutMillis(Duration.ofSeconds(1)));
  }

  @Test
  @DisplayName("Postgres cancels a slow statement itself and the connection stays usable")
  void postgresCancelsTheStatement() throws SQLException {
    try (HikariDataSource pool = pool(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
        DatabaseEngine.POSTGRES); Connection connection = pool.getConnection();
         Statement statement = connection.createStatement()) {
      long started = System.nanoTime();
      SQLException cancelled = assertThrows(SQLException.class, () -> statement.execute("SELECT pg_sleep(30)"));
      long tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

      assertEquals("57014", cancelled.getSQLState(), cancelled.getMessage());
      assertTrue(tookMillis < READ_TIMEOUT.toMillis(), "cancelled by the database before the socket gave up: " + tookMillis);
      try (ResultSet rows = statement.executeQuery("SHOW idle_in_transaction_session_timeout")) {
        rows.next();
        assertEquals("3600ms", rows.getString(1));
      }
    }
  }

  @Test
  @DisplayName("MySQL cancels a slow read itself")
  void mysqlCancelsTheStatement() throws SQLException {
    try (HikariDataSource pool = pool(mysql.getJdbcUrl(), "root", mysql.getPassword(), DatabaseEngine.MYSQL);
         Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
      long started = System.nanoTime();
      assertThrows(SQLException.class, () -> statement.executeQuery(
          "SELECT COUNT(*) FROM information_schema.columns a, information_schema.columns b, "
              + "information_schema.columns c, information_schema.columns d"));
      long tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

      assertTrue(tookMillis < READ_TIMEOUT.toMillis(), "cancelled by the database before the socket gave up: " + tookMillis);
      try (ResultSet rows = statement.executeQuery("SELECT @@SESSION.max_execution_time")) {
        rows.next();
        assertEquals(STATEMENT_MILLIS, rows.getLong(1));
      }
    }
  }
}
