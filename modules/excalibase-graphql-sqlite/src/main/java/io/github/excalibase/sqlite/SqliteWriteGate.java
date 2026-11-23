package io.github.excalibase.sqlite;

import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final ConcurrentMap<String, Shared> FILES = new ConcurrentHashMap<>();
    private static final String BUSY_MESSAGE = "[SQLITE_BUSY] The database file is locked by another writer";
    private static final String JDBC_PREFIX = "jdbc:sqlite:";
    private static final int CODE_BUSY = 5;

    /** The turn of one file, and how many connections to it are open: the last one to close removes it. */
    private static final class Shared {
        final Semaphore turn = new Semaphore(1, true);
        int connections;
    }

    private final String file;
    private final Shared shared;
    private final long waitMillis;
    private final AtomicBoolean holding = new AtomicBoolean();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile boolean autoCommit = true;

    private SqliteWriteGate(String file, Shared shared, long waitMillis) {
        this.file = file;
        this.shared = shared;
        this.waitMillis = waitMillis;
    }

    /**
     * The gate of {@code connection}, shared with every other connection to the same file, by its real path whatever
     * the URL's parameters. A database in memory belongs to its one connection: it has no gate.
     */
    static SqliteWriteGate of(Connection connection) {
        try {
            long wait = connection.unwrap(SQLiteConnection.class).getBusyTimeout();
            String file = canonicalFile(connection.getMetaData().getURL());
            if (file == null) {
                return new SqliteWriteGate(null, null, wait);
            }
            Shared shared = FILES.compute(file, (key, existing) -> {
                Shared current = existing == null ? new Shared() : existing;
                current.connections++;
                return current;
            });
            return new SqliteWriteGate(file, shared, wait);
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot tell which SQLite file the connection is to", failure);
        }
    }

    /** The file a SQLite URL names, resolved, or null for a database in memory. */
    static String canonicalFile(String url) {
        String path = url.startsWith(JDBC_PREFIX) ? url.substring(JDBC_PREFIX.length()) : url;
        int parameters = path.indexOf('?');
        String query = parameters < 0 ? "" : path.substring(parameters);
        path = parameters < 0 ? path : path.substring(0, parameters);
        if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }
        if (path.isEmpty() || path.contains(":memory:") || query.contains("mode=memory")) {
            return null;
        }
        Path resolved = Path.of(path).toAbsolutePath().normalize();
        try {
            return resolved.toRealPath().toString();
        } catch (IOException missing) {
            return resolved.toString();
        }
    }

    /** Whether any gate is kept for {@code url}'s file; the map must not outlive the connections. */
    static boolean kept(String url) {
        String file = canonicalFile(url);
        return file != null && FILES.containsKey(file);
    }

    /** Whether writers of this file wait in arrival order. */
    boolean fair() {
        return shared != null && shared.turn.isFair();
    }

    /** The connection is closed: it gives back its turn, and the last of its file's connections drops the gate. */
    void close() {
        end();
        if (file != null && open.compareAndSet(true, false)) {
            FILES.computeIfPresent(file, (key, existing) -> --existing.connections == 0 ? null : existing);
        }
    }

    /** Takes this connection's turn before its first write of a transaction; a no-op once it has it. */
    void beforeWrite(String sql) throws SQLException {
        if (shared == null || holding.get() || !SqliteWrites.writes(sql)) {
            return;
        }
        try {
            if (!shared.turn.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
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
            shared.turn.release();
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
}
