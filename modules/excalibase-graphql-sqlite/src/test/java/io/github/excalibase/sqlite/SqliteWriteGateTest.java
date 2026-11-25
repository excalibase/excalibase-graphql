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

            assertThat(parkedWithin(waiter)).as("a waiting writer is parked, not running in the busy handler").isTrue();
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
        try (Connection stillOpen = open(file); Connection second = open(file)) {
            first.setAutoCommit(false);
            try (Statement statement = first.createStatement()) {
                statement.executeUpdate("INSERT INTO t VALUES (1, 1)");
            }
            first.close();

            assertThat(stillOpen.isClosed()).as("the connection that keeps the gate").isFalse();
            assertThat(SqliteWriteGate.kept("jdbc:sqlite:" + file)).as("another connection keeps the gate").isTrue();
            try (Statement statement = second.createStatement()) {
                assertThat(statement.executeUpdate("INSERT INTO t VALUES (2, 2)")).isEqualTo(1);
            }
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

    private static Connection openShort(Path file, String parameters) throws SQLException {
        return StatementWatchdog.wrap(DriverManager.getConnection("jdbc:sqlite:" + file + "?busy_timeout=300" + parameters),
                LIMIT_MILLIS);
    }

    private static void holdTurn(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO t VALUES (100, 1)");
        }
    }

    private static boolean waitsInJava(Connection other, ThrowingRun statement) throws Exception {
        Thread waiter = Thread.ofVirtual().start(() -> {
            try (Statement run = other.createStatement()) {
                statement.run(run);
            } catch (SQLException _) {
                // the wait is what is measured, not how it ends
            }
        });
        boolean parked = parkedWithin(waiter);
        waiter.join(2_000);
        return parked;
    }

    /** True once {@code thread} is parked; false if it finishes, or is still running after two seconds. */
    private static boolean parkedWithin(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return true;
            }
            if (state == Thread.State.TERMINATED) {
                return false;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    @FunctionalInterface
    private interface ThrowingRun {
        void run(Statement statement) throws SQLException;
    }

    @Test
    void turningAutoCommitBackOn_endsTheTransactionAndGivesTheTurnBack() throws Exception {
        Path file = Files.createTempFile("gate", ".db");
        create(file);
        try (Connection first = openShort(file, ""); Connection second = openShort(file, "")) {
            holdTurn(first);
            first.setAutoCommit(true);

            try (Statement statement = second.createStatement()) {
                assertThat(statement.executeUpdate("INSERT INTO t VALUES (2, 2)")).isEqualTo(1);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aWithThatWrites_isGated_andOneThatOnlyReads_isNot() throws Exception {
        Path file = Files.createTempFile("gate", ".db");
        create(file);
        try (Connection first = openShort(file, ""); Connection second = openShort(file, "")) {
            holdTurn(first);

            assertThat(waitsInJava(second, statement -> statement.executeUpdate(
                    "WITH x AS (SELECT 1) INSERT INTO t SELECT 7, 7 FROM x"))).isTrue();
            assertThat(waitsInJava(second, statement -> statement.executeQuery(
                    "WITH \"update\" AS (SELECT 'insert' AS v, replace('a', 'a', 'b') AS r) SELECT * FROM \"update\"")))
                    .isFalse();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void batchedStatements_areGated() throws Exception {
        Path file = Files.createTempFile("gate", ".db");
        create(file);
        try (Connection first = openShort(file, ""); Connection second = openShort(file, "")) {
            holdTurn(first);

            assertThat(waitsInJava(second, statement -> {
                statement.addBatch("INSERT INTO t VALUES (8, 8)");
                statement.executeBatch();
            })).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void writersOfAFile_waitInArrivalOrder() throws Exception {
        Path file = Files.createTempFile("gate", ".db");
        create(file);
        try (Connection connection = openShort(file, "")) {
            assertThat(SqliteWriteGate.of(connection).fair()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void separateFiles_haveSeparateGates() throws Exception {
        Path one = Files.createTempFile("gate", ".db");
        Path other = Files.createTempFile("gate", ".db");
        create(one);
        create(other);
        try (Connection first = openShort(one, ""); Connection elsewhere = openShort(other, "")) {
            holdTurn(first);

            try (Statement statement = elsewhere.createStatement()) {
                assertThat(statement.executeUpdate("INSERT INTO t VALUES (2, 2)")).isEqualTo(1);
            }
        } finally {
            Files.deleteIfExists(one);
            Files.deleteIfExists(other);
        }
    }

    @Test
    void theSameFileWithOtherUrlParameters_isOneGate() throws Exception {
        Path file = Files.createTempFile("gate", ".db");
        create(file);
        try (Connection first = openShort(file, ""); Connection second = openShort(file, "&foreign_keys=true")) {
            holdTurn(first);

            assertThat(waitsInJava(second, statement -> statement.executeUpdate("INSERT INTO t VALUES (9, 9)"))).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void databasesInMemory_neverWaitForEachOther() throws Exception {
        try (Connection first = StatementWatchdog.wrap(DriverManager.getConnection("jdbc:sqlite::memory:"), LIMIT_MILLIS);
             Connection second = StatementWatchdog.wrap(DriverManager.getConnection("jdbc:sqlite::memory:"), LIMIT_MILLIS)) {
            for (Connection connection : new Connection[] {first, second}) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE t (id INTEGER)");
                }
            }
            first.setAutoCommit(false);
            try (Statement statement = first.createStatement()) {
                statement.executeUpdate("INSERT INTO t VALUES (1)");
            }

            assertThat(waitsInJava(second, statement -> statement.executeUpdate("INSERT INTO t VALUES (2)"))).isFalse();
        }
    }

    @Test
    void theGateOfAFile_isDroppedWithItsLastConnection() throws Exception {
        Path file = Files.createTempFile("gate", ".db");
        create(file);
        String url = "jdbc:sqlite:" + file;
        Connection first = open(file);
        Connection second = open(file);
        assertThat(SqliteWriteGate.kept(url)).isTrue();
        first.close();
        assertThat(SqliteWriteGate.kept(url)).as("one connection is still open").isTrue();
        second.close();

        assertThat(SqliteWriteGate.kept(url)).isFalse();
        Files.deleteIfExists(file);
    }
}
