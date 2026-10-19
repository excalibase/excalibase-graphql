package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariConfig;
import io.github.excalibase.service.DatabaseEngine;

import java.time.Duration;

/**
 * Time bounds for a database pool (EXC-650). Without a read bound a pooled connection to a server
 * that stopped answering (its pod replaced by a restore or failover) holds the request until the
 * edge gives up; with one the call fails, the connection is evicted and the next call dials anew.
 */
final class ConnectionBounds {

  /**
   * Longest wait for one answer. It also caps a single statement, since there is no per-request
   * statement timeout, so it sits well above a normal query.
   */
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

  /** Bounds connect, login, the borrow check and every read; pgjdbc counts seconds, Connector/J milliseconds. */
  static void apply(HikariConfig config, DatabaseEngine engine, Duration readTimeout) {
    requireUsable(readTimeout);
    config.setConnectionTimeout(CONNECT_TIMEOUT_SECONDS * 1000L);
    config.setValidationTimeout(VALIDATION_TIMEOUT_MS);
    if (engine == DatabaseEngine.MYSQL) {
      config.addDataSourceProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS * 1000));
      config.addDataSourceProperty("socketTimeout", String.valueOf(readTimeout.toMillis()));
    } else {
      config.addDataSourceProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
      config.addDataSourceProperty("loginTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
      config.addDataSourceProperty("socketTimeout", String.valueOf(readTimeout.toSeconds()));
    }
  }
}
