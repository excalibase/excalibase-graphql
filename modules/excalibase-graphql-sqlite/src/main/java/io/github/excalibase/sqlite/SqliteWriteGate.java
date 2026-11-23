package io.github.excalibase.sqlite;

import org.sqlite.SQLiteConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Lets one connection at a time write a file, and makes the others wait in Java, in the order they came. SQLite's own
 * busy handler waits inside native code, which holds a virtual thread's carrier: with fewer carriers than waiting
 * writers, the one holding the write lock cannot run to release it and every waiter times out. It is also
 * unfair, so a writer can lose the race for the whole timeout. The busy timeout stays, for a writer in another
 * process, and is how long a writer waits here.
 *
 * <p>A connection takes its turn at its first write and gives it back when its transaction ends: commit, rollback,
 * the end of a statement run with auto-commit, or close.
 */
final class SqliteWriteGate {

    private static final ConcurrentMap<String, Semaphore> TURNS = new ConcurrentHashMap<>();
    private static final Pattern WRITE_WORD = Pattern.compile("\\b(INSERT|UPDATE|DELETE|REPLACE)\\b");
    private static final Pattern LEADING_NOISE = Pattern.compile("^(\\s+|--[^\\n]*\\n?|/\\*.*?\\*/)+", Pattern.DOTALL);
    private static final String BUSY_MESSAGE = "[SQLITE_BUSY] The database file is locked by another writer";
    private static final int CODE_BUSY = 5;

    private final Semaphore turn;
    private final long waitMillis;
    private final AtomicBoolean holding = new AtomicBoolean();
    private volatile boolean autoCommit = true;

    private SqliteWriteGate(Semaphore turn, long waitMillis) {
        this.turn = turn;
        this.waitMillis = waitMillis;
    }

    /** The gate of {@code connection}, shared with every other connection to the same file. */
    static SqliteWriteGate of(Connection connection) {
        try {
            String file = connection.getMetaData().getURL();
            long wait = connection.unwrap(SQLiteConnection.class).getBusyTimeout();
            return new SqliteWriteGate(TURNS.computeIfAbsent(file, key -> new Semaphore(1, true)), wait);
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot tell which SQLite file the connection is to", failure);
        }
    }

    /** Takes this connection's turn before its first write of a transaction; a no-op once it has it. */
    void beforeWrite(String sql) throws SQLException {
        if (holding.get() || !writes(sql)) {
            return;
        }
        try {
            if (!turn.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                throw new SQLException(BUSY_MESSAGE, "SQLITE_BUSY", CODE_BUSY);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while waiting for the database file", interrupted);
        }
        holding.set(true);
    }

    /** The transaction ended, or the connection is done: the next writer may go. */
    void end() {
        if (holding.compareAndSet(true, false)) {
            turn.release();
        }
    }

    /** A statement was closed: with auto-commit, that is the end of the write it ran. */
    void statementClosed() {
        if (autoCommit) {
            end();
        }
    }

    void autoCommitSet(boolean enabled) {
        autoCommit = enabled;
        if (enabled) {
            end();
        }
    }

    /** Whether {@code sql} takes SQLite's write lock; what cannot be told is left to the busy timeout. */
    static boolean writes(String sql) {
        if (sql == null) {
            return false;
        }
        String text = LEADING_NOISE.matcher(sql).replaceFirst("");
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.startsWith("WITH")) {
            return WRITE_WORD.matcher(upper).find();
        }
        if (upper.startsWith("PRAGMA")) {
            return upper.contains("=");
        }
        if (upper.startsWith("BEGIN")) {
            return upper.contains("IMMEDIATE") || upper.contains("EXCLUSIVE");
        }
        return upper.startsWith("INSERT") || upper.startsWith("UPDATE") || upper.startsWith("DELETE")
                || upper.startsWith("REPLACE") || upper.startsWith("CREATE") || upper.startsWith("DROP")
                || upper.startsWith("ALTER") || upper.startsWith("VACUUM") || upper.startsWith("REINDEX");
    }
}
