package io.github.excalibase.sqlite;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A writer that waits for another writer of the same file waits in Java, where a virtual thread gives its carrier
 * back, never inside SQLite's busy handler, which blocks the carrier in native code: with fewer carriers than
 * waiting writers the one holding the lock then cannot run to release it, and every waiter times out.
 */
class SqliteWriteGateTest {

    private static final long LIMIT_MILLIS = 60_000;

    private static Connection open(Path file) throws SQLException {
        return StatementWatchdog.wrap(DriverManager.getConnection("jdbc:sqlite:" + file + "?busy_timeout=10000"), LIMIT_MILLIS);
    }

    private static void create(Path file) throws SQLException {
        try (Connection connection = open(file); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v INTEGER)");
        }
    }

    @Test
    void aSecondWriter_waitsInJava_notInsideSqlite_untilTheFirstCommits() throws Exception {
        Path file = Files.createTempFile("gate", ".db");
        create(file);
        try (Connection first = open(file); Connection second = open(file)) {
            first.setAutoCommit(false);
            try (Statement statement = first.createStatement()) {
                statement.executeUpdate("INSERT INTO t VALUES (1, 1)");
            }
            CountDownLatch writing = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread waiter = Thread.ofVirtual().start(() -> {
                try (Statement statement = second.createStatement()) {
                    writing.countDown();
                    statement.executeUpdate("INSERT INTO t VALUES (2, 2)");
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);

            assertThat(waiter.getState()).as("a waiting writer is parked, not running in the busy handler")
                    .isIn(Thread.State.WAITING, Thread.State.TIMED_WAITING);
            first.commit();
            waiter.join(5_000);
            assertThat(failure.get()).isNull();
            try (Statement statement = second.createStatement()) {
                assertThat(statement.executeQuery("SELECT count(*) FROM t").getInt(1)).isEqualTo(2);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aRolledBackWriter_letsTheNextOneIn() throws Exception {
        Path file = Files.createTempFile("gate", ".db");
        create(file);
        try (Connection first = open(file); Connection second = open(file)) {
            first.setAutoCommit(false);
            try (Statement statement = first.createStatement()) {
                statement.executeUpdate("INSERT INTO t VALUES (1, 1)");
            }
            first.rollback();

            try (Statement statement = second.createStatement()) {
                assertThat(statement.executeUpdate("INSERT INTO t VALUES (2, 2)")).isEqualTo(1);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aWriterThatClosesWithoutEnding_letsTheNextOneIn() throws Exception {
        Path file = Files.createTempFile("gate", ".db");
        create(file);
        Connection first = open(file);
        first.setAutoCommit(false);
        try (Statement statement = first.createStatement()) {
            statement.executeUpdate("INSERT INTO t VALUES (1, 1)");
        }
        first.close();
        try (Connection second = open(file); Statement statement = second.createStatement()) {
            assertThat(statement.executeUpdate("INSERT INTO t VALUES (2, 2)")).isEqualTo(1);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aWriterWaitingLongerThanTheBusyTimeout_isBusy() throws Exception {
        Path file = Files.createTempFile("gate", ".db");
        create(file);
        try (Connection first = StatementWatchdog.wrap(DriverManager.getConnection("jdbc:sqlite:" + file), LIMIT_MILLIS);
             Connection second = StatementWatchdog.wrap(
                     DriverManager.getConnection("jdbc:sqlite:" + file + "?busy_timeout=300"), LIMIT_MILLIS)) {
            first.setAutoCommit(false);
            try (Statement statement = first.createStatement()) {
                statement.executeUpdate("INSERT INTO t VALUES (1, 1)");
            }
            try (Statement statement = second.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO t VALUES (2, 2)"))
                        .isInstanceOfSatisfying(SQLException.class, busy ->
                                assertThat(SqliteErrors.describe(busy)).hasValueSatisfying(error ->
                                        assertThat(error.code()).isEqualTo("database_busy")));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
