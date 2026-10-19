package io.github.excalibase.service;

/** The database a project runs on, as its vault record names it. */
public enum DatabaseEngine {
  POSTGRES("postgres", "jdbc:postgresql://"),
  MYSQL("mysql", "jdbc:mysql://");

  private final String type;
  private final String jdbcPrefix;

  DatabaseEngine(String type, String jdbcPrefix) {
    this.type = type;
    this.jdbcPrefix = jdbcPrefix;
  }

  /** The {@code app.database-type} name the engine's dialect is registered under. */
  public String type() {
    return type;
  }

  /** The engine a JDBC URL's driver speaks; anything not MySQL is Postgres, the default dialect. */
  public static DatabaseEngine ofJdbcUrl(String url) {
    return url != null && url.startsWith(MYSQL.jdbcPrefix) ? MYSQL : POSTGRES;
  }

  String jdbcPrefix() {
    return jdbcPrefix;
  }
}
