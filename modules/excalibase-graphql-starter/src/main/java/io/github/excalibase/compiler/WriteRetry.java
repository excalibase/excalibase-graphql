package io.github.excalibase.compiler;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Whether a write failed because it lost a lock to a concurrent request: the database rolled the transaction
 * back, and running it again from the start can succeed. The owner of the transaction runs it again, and only
 * when the whole request is that one transaction, so nothing already done is repeated.
 *
 * <p>A MySQL deadlock (1213) is the expected loser of two upserts of one new key under REPEATABLE READ, and
 * losing again to the same rival is likely, so it gets {@link #DEADLOCK_ATTEMPTS} attempts, each after a random
 * pause that grows. Every other conflict keeps a single retry.
 */
public final class WriteRetry {

    /** Attempts a transaction gets after a conflict other than a MySQL deadlock: the first, and one retry. */
    public static final int ATTEMPTS = 2;

    /** Attempts a transaction gets after a MySQL deadlock. */
    public static final int DEADLOCK_ATTEMPTS = 6;

    private static final long BACKOFF_BASE_MILLIS = 5;
    private static final long BACKOFF_CAP_MILLIS = 200;

    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String POSTGRES_DEADLOCK = "40P01";

    private WriteRetry() {
    }

    /** True when {@code failure}, or a cause of it, is a MySQL deadlock. */
    public static boolean isMysqlDeadlock(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == MYSQL_DEADLOCK) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a transaction that failed with {@code failure} on its {@code attempt}th try (from 1) runs again.
     * When it does, the caller pauses {@link #pause} first.
     */
    public static boolean shouldRetry(Throwable failure, int attempt) {
        int allowed = isMysqlDeadlock(failure) ? DEADLOCK_ATTEMPTS : ATTEMPTS;
        return attempt < allowed && isLockConflict(failure);
    }

    /** Sleeps a random time up to an exponentially growing bound, so the losers of a deadlock do not collide again in step. */
    public static void pause(int attempt) {
        long bound = Math.min(BACKOFF_CAP_MILLIS, BACKOFF_BASE_MILLIS << Math.min(attempt, 6));
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, bound + 1));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /** True when {@code failure}, or a cause of it, is a lost lock or a deadlock. */
    public static boolean isLockConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql && isLockConflict(sql)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLockConflict(SQLException sql) {
        return sql.getErrorCode() == MYSQL_DEADLOCK || sql.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT
                || SERIALIZATION_FAILURE.equals(sql.getSQLState()) || POSTGRES_DEADLOCK.equals(sql.getSQLState());
    }
}
