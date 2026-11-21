package io.github.excalibase.sqlite;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementDeadlineTest {

    private static final String ENDLESS = "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r)"
            + " SELECT count(*) FROM r";

    @Test
    void aStatementThatRunsPastTheLimit_isInterrupted_andTheConnectionStaysUsable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            StatementDeadline.install(connection, 500);
            long started = System.nanoTime();

            SQLException interrupted = assertThrows(SQLException.class,
                    () -> statement.executeQuery(ENDLESS).next());

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(400), Duration.ofSeconds(10));
            assertThat(SqliteErrors.describe(interrupted)).hasValueSatisfying(error -> {
                assertThat(error.status()).isEqualTo(503);
                assertThat(error.code()).isEqualTo("statement_timeout");
            });
            assertThat(statement.executeQuery("SELECT 1").getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void shortStatements_neverAddUpToTheLimit_whenTheConnectionRestsBetweenThem() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            StatementDeadline.install(connection, 600);
            for (int i = 0; i < 4; i++) {
                assertThat(statement.executeQuery("WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r"
                        + " WHERE n < 200000) SELECT count(*) FROM r").getInt(1)).isEqualTo(200000);
                Thread.sleep(300);
            }
        }
    }

    @Test
    void aBusyDatabase_isARetryableUnavailable_notAnInternalError() throws Exception {
        Path file = Files.createTempFile("busy", ".db");
        try (Connection holder = DriverManager.getConnection("jdbc:sqlite:" + file);
             Connection waiter = DriverManager.getConnection("jdbc:sqlite:" + file + "?busy_timeout=0");
             Statement hold = holder.createStatement(); Statement wait = waiter.createStatement()) {
            hold.execute("CREATE TABLE t (id INTEGER)");
            hold.execute("BEGIN IMMEDIATE");

            assertThatThrownBy(() -> wait.execute("BEGIN IMMEDIATE")).isInstanceOfSatisfying(SQLException.class, busy ->
                    assertThat(SqliteErrors.describe(busy)).hasValueSatisfying(error -> {
                        assertThat(error.status()).isEqualTo(503);
                        assertThat(error.code()).isEqualTo("database_busy");
                        assertThat(error.message()).doesNotContain(file.toString());
                    }));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
