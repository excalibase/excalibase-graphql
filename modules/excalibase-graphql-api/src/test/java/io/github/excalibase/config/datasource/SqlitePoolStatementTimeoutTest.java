package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.excalibase.service.DatabaseEngine;
import io.github.excalibase.support.SqliteFile;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The pool the engine builds for a SQLite file bounds each statement by the same read timeout as a server pool. */
class SqlitePoolStatementTimeoutTest {

    private static void runEndless(Statement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery(
                "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r) SELECT count(*) FROM r")) {
            rows.next();
        }
    }

    @Test
    void aSlowQuery_isCutOff_atTheStatementTimeoutOfTheReadTimeout() throws Exception {
        SqliteFile file = SqliteFile.create("CREATE TABLE t (id INTEGER PRIMARY KEY);");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(file.url());
        ConnectionBounds.apply(config, DatabaseEngine.SQLITE, Duration.ofSeconds(1));
        try (HikariDataSource pool = new HikariDataSource(config); Connection connection = pool.getConnection();
             Statement statement = connection.createStatement()) {
            long started = System.nanoTime();

            assertThrows(SQLException.class, () -> runEndless(statement));

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
            assertThat(statement.executeQuery("SELECT 1").getInt(1)).isEqualTo(1);
        }
    }
}
