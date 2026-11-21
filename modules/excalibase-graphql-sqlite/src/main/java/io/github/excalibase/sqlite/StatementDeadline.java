package io.github.excalibase.sqlite;

import org.sqlite.ProgressHandler;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Bounds how long one statement may run on a connection (EXC-711). sqlite-jdbc's {@code setQueryTimeout} only sets the
 * lock wait and never stops a running query, so the bound is a progress handler: SQLite calls it every few thousand
 * virtual machine steps of a running statement, and a non-zero answer interrupts it. The handler cannot see where a
 * statement begins, so a pause of more than {@link #IDLE_GAP} between two calls (a lock wait, the next request)
 * starts a new run; only continuous execution counts against the limit.
 */
final class StatementDeadline extends ProgressHandler {

    private static final int STEPS_BETWEEN_CHECKS = 1_000;
    private static final long IDLE_GAP = TimeUnit.MILLISECONDS.toNanos(200);
    private static final int INTERRUPT = 1;

    private final long limitNanos;
    private long runStart;
    private long lastCall;

    private StatementDeadline(long limitMillis) {
        this.limitNanos = TimeUnit.MILLISECONDS.toNanos(limitMillis);
    }

    static void install(Connection connection, long limitMillis) throws SQLException {
        ProgressHandler.setHandler(connection, STEPS_BETWEEN_CHECKS, new StatementDeadline(limitMillis));
    }

    @Override
    protected int progress() {
        long now = System.nanoTime();
        if (now - lastCall > IDLE_GAP) {
            runStart = now;
        }
        lastCall = now;
        return now - runStart > limitNanos ? INTERRUPT : 0;
    }
}
