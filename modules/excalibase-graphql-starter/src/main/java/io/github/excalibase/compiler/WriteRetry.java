package io.github.excalibase.compiler;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Whether a write failed because it lost a lock to a concurrent request: the database rolled the transaction
 * back, and running it again from the start can succeed. The owner of the transaction runs it again, and only
 * when the whole request is that one transaction, so nothing already done is repeated.
 *
 * <p>A MySQL deadlock (1213) is the expected loser of two upserts of one new key under REPEATABLE READ, and
 * losing again to the same rival is likely, so it gets {@link #DEADLOCK_ATTEMPTS} attempts, each after a random
 * pause of at most 10, 20, 40, 80 and 160 ms. A Postgres deadlock or serialization failure keeps a single
 * retry, after a pause of 1 to 10 ms. A MySQL lock wait timeout is not retried: the request already waited
 * for the lock, and waiting again would double a slow request. No retry starts once {@link #BUDGET} of the
 * request's time is spent, or after the thread is interrupted.
 */
public final class WriteRetry {

    /** Attempts a transaction gets after a conflict other than a MySQL deadlock: the first, and one retry. */
    public static final int ATTEMPTS = 2;

    /** Attempts a transaction gets after a MySQL deadlock. */
    public static final int DEADLOCK_ATTEMPTS = 6;

    /** How long a request may spend, from its first attempt, before a lost lock is no longer retried. */
    public static final Duration BUDGET = Duration.ofSeconds(5);

    private static final long BACKOFF_BASE_MILLIS = 5;

    private static final int MYSQL_DEADLOCK = 1213;
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String POSTGRES_DEADLOCK = "40P01";

    private WriteRetry() {
    }

    /** Sleeps for {@code millis}; false when the thread was interrupted, which it keeps flagged. */
    @FunctionalInterface
    interface Sleeper {
        boolean sleep(long millis);
    }

    /** One request's attempts: counts them, and spends the budget. */
    public static final class Run {
        private final LongSupplier nanoClock;
        private final Sleeper sleeper;
        private final long startNanos;
        private int attempt = 1;
        private Exception lastFailure;

        public Run() {
            this(System::nanoTime, Run::sleepMillis);
        }

        Run(LongSupplier nanoClock, Sleeper sleeper) {
            this.nanoClock = nanoClock;
            this.sleeper = sleeper;
            this.startNanos = nanoClock.getAsLong();
        }

        /** The attempt now running, from 1. */
        public int attempt() {
            return attempt;
        }

        /** The failure last judged by {@link #canRetry}. */
        public Exception lastFailure() {
            return lastFailure;
        }

        /** Whether the attempt that failed with {@code failure} may run again; does not wait. */
        public boolean canRetry(Exception failure) {
            lastFailure = failure;
            int allowed = isMysqlDeadlock(failure) ? DEADLOCK_ATTEMPTS : ATTEMPTS;
            return attempt < allowed && isLockConflict(failure)
                    && nanoClock.getAsLong() - startNanos < BUDGET.toNanos();
        }

        /** Waits a random time, longer after each attempt, so the losers of a deadlock do not collide again in step. */
        public boolean pause() {
            long bound = BACKOFF_BASE_MILLIS << attempt;
            if (!sleeper.sleep(ThreadLocalRandom.current().nextLong(1, bound + 1))) return false;
            attempt++;
            return true;
        }

        /** {@link #canRetry} and then {@link #pause}: true when the transaction should run again now. */
        public boolean retry(Exception failure) {
            return canRetry(failure) && pause();
        }

        private static boolean sleepMillis(long millis) {
            try {
                Thread.sleep(millis);
                return true;
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
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

    /** True when {@code failure}, or a cause of it, is a deadlock or serialization failure. */
    public static boolean isLockConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql && isLockConflict(sql)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLockConflict(SQLException sql) {
        return sql.getErrorCode() == MYSQL_DEADLOCK
                || SERIALIZATION_FAILURE.equals(sql.getSQLState()) || POSTGRES_DEADLOCK.equals(sql.getSQLState());
    }
}
