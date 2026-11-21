package io.github.excalibase.compiler;

import java.sql.SQLException;

/**
 * Whether a write failed because it lost a lock to a concurrent request: the database rolled the transaction
 * back, and running it again from the start can succeed. The owner of the transaction retries once.
 */
public final class WriteRetry {

    /** Attempts a transaction gets: the first, and one retry. */
    public static final int ATTEMPTS = 2;

    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String POSTGRES_DEADLOCK = "40P01";

    private WriteRetry() {
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
