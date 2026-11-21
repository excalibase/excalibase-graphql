package io.github.excalibase.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(60)
class StatementWatchdogTest {

    private static final String ENDLESS = "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r)"
            + " SELECT count(*) FROM r";

    private static void runEndless(Statement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery(ENDLESS)) {
            rows.next();
        }
    }

    @Test
    void aStatementThatRunsPastTheLimit_isInterrupted_andTheConnectionStaysUsable() throws Exception {
        try (Connection connection = StatementWatchdog.wrap(DriverManager.getConnection("jdbc:sqlite::memory:"), 500);
             Statement statement = connection.createStatement()) {
            long started = System.nanoTime();

            SQLException interrupted = assertThrows(SQLException.class,
                    () -> runEndless(statement));

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(400), Duration.ofSeconds(10));
            assertThat(SqliteErrors.describe(interrupted)).hasValueSatisfying(error -> {
                assertThat(error.status()).isEqualTo(503);
                assertThat(error.code()).isEqualTo("statement_timeout");
            });
            assertThat(statement.executeQuery("SELECT 1").getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void shortStatementsRunBackToBack_pastTheLimit_areNeverInterrupted() throws Exception {
        try (Connection connection = StatementWatchdog.wrap(DriverManager.getConnection("jdbc:sqlite::memory:"), 400);
             Statement statement = connection.createStatement()) {
            long started = System.nanoTime();
            int run = 0;
            while (Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(1500)) < 0) {
                try (ResultSet rows = statement.executeQuery("WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r"
                        + " WHERE n < 20000) SELECT count(*) FROM r")) {
                    assertThat(rows.getInt(1)).isEqualTo(20000);
                }
                run++;
            }
            assertThat(run).isGreaterThan(10);
        }
    }

    @Test
    void aPreparedStatementIsBoundedToo() throws Exception {
        try (Connection connection = StatementWatchdog.wrap(DriverManager.getConnection("jdbc:sqlite::memory:"), 400);
             PreparedStatement statement = connection.prepareStatement(ENDLESS)) {
            SQLException interrupted = assertThrows(SQLException.class, () -> {
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                }
            });

            assertThat(SqliteErrors.describe(interrupted)).hasValueSatisfying(error ->
                    assertThat(error.code()).isEqualTo("statement_timeout"));
        }
    }

    @Test
    void shortStatements_neverAddUpToTheLimit_whenTheConnectionRestsBetweenThem() throws Exception {
        try (Connection connection = StatementWatchdog.wrap(DriverManager.getConnection("jdbc:sqlite::memory:"), 600);
             Statement statement = connection.createStatement()) {
            for (int i = 0; i < 4; i++) {
                assertThat(statement.executeQuery("WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r"
                        + " WHERE n < 200000) SELECT count(*) FROM r").getInt(1)).isEqualTo(200000);
                LockSupport.parkNanos(Duration.ofMillis(300).toNanos());
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
