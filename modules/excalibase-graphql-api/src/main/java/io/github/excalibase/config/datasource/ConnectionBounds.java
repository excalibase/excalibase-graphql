package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariConfig;
import io.github.excalibase.service.DatabaseEngine;
import io.github.excalibase.spi.SqlEngineFactory;

import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;

/**
 * Time bounds for a database pool (EXC-650). Without a read bound a pooled connection to a server
 * that stopped answering (its pod replaced by a restore or failover) holds the request until the
 * edge gives up; with one the call fails, the connection is evicted and the next call dials anew.
 */
final class ConnectionBounds {

  /** Longest wait for one answer; the database's own statement timeout sits just below it (EXC-711). */
  static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

  // Dial and login; a reachable server answers both in well under a second.
  private static final int CONNECT_TIMEOUT_SECONDS = 10;
  // The liveness check on borrow; each stale idle connection costs one.
  private static final long VALIDATION_TIMEOUT_MS = 2_000;

  private ConnectionBounds() {
  }

  static Duration requireUsable(Duration readTimeout) {
    if (readTimeout.compareTo(Duration.ofSeconds(1)) < 0) {
      throw new IllegalArgumentException("the database read timeout must be at least 1 second, got: " + readTimeout);
    }
    return readTimeout;
  }

  /**
   * How long the database may spend on one statement: a tenth under the socket timeout, so the
   * database cancels the work itself before the engine gives up on the socket and leaves it running.
   */
  static long statementTimeoutMillis(Duration readTimeout) {
    return Math.max(500, readTimeout.toMillis() * 9 / 10);
  }

  /**
   * The session settings every pooled connection starts with: Postgres {@code statement_timeout} and
   * {@code idle_in_transaction_session_timeout}, MySQL {@code max_execution_time} (reads only; MySQL has no
   * limit for a write, which the socket timeout alone bounds).
   */
  static String sessionInit(DatabaseEngine engine, Duration readTimeout) {
    long millis = statementTimeoutMillis(readTimeout);
    if (engine == DatabaseEngine.MYSQL) {
      return "SET SESSION max_execution_time = " + millis;
    }
    return "SET statement_timeout = " + millis + "; SET idle_in_transaction_session_timeout = " + millis;
  }

  /** Adds {@code assignment} to the MySQL init statement {@link #apply} set: one {@code SET} cannot be two. */
  static void addMysqlAssignment(HikariConfig config, String assignment) {
    config.setConnectionInitSql(config.getConnectionInitSql() + ", " + assignment);
  }

  /** The file's pool: each connection opened with the plugin's properties and handed to it to bound its statements. */
  private static void useBoundedFile(HikariConfig config, DatabaseEngine engine, long statementTimeoutMillis) {
    Properties properties = new Properties();
    properties.putAll(SqlEngineFactory.connectionProperties(engine.type()));
    DriverManagerDataSource files = new DriverManagerDataSource(config.getJdbcUrl());
    files.setConnectionProperties(properties);
    config.setDataSource(new DelegatingDataSource(files) {
      @Override
      public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        try {
          SqlEngineFactory.prepareConnection(engine.type(), connection, statementTimeoutMillis);
        } catch (SQLException | RuntimeException failure) {
          connection.close();
          throw failure;
        }
        return connection;
      }
    });
  }

  /** Bounds connect, login, the borrow check and every read; pgjdbc counts seconds, Connector/J milliseconds. */
  static void apply(HikariConfig config, DatabaseEngine engine, Duration readTimeout) {
    requireUsable(readTimeout);
    config.setConnectionTimeout(CONNECT_TIMEOUT_SECONDS * 1000L);
    config.setValidationTimeout(VALIDATION_TIMEOUT_MS);
    if (engine == DatabaseEngine.SQLITE) {
      // A file has no server to dial or to stop answering; its waits are the busy timeout of each connection, and the
      // plugin bounds each running statement as session SQL would on a server database.
      useBoundedFile(config, engine, statementTimeoutMillis(readTimeout));
      return;
    }
    if (engine == DatabaseEngine.MYSQL) {
      config.addDataSourceProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS * 1000));
      config.addDataSourceProperty("socketTimeout", String.valueOf(readTimeout.toMillis()));
    } else {
      config.addDataSourceProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
      config.addDataSourceProperty("loginTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
      config.addDataSourceProperty("socketTimeout", String.valueOf(readTimeout.toSeconds()));
    }
    config.setConnectionInitSql(sessionInit(engine, readTimeout));
  }
}
