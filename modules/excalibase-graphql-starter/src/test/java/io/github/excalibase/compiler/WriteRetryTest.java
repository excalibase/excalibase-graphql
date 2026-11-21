package io.github.excalibase.compiler;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WriteRetryTest {

    private static final SQLException MYSQL_DEADLOCK = new SQLException("Deadlock found", "40001", 1213);
    private static final SQLException MYSQL_LOCK_TIMEOUT = new SQLException("Lock wait timeout exceeded", "40001", 1205);
    private static final SQLException POSTGRES_DEADLOCK = new SQLException("deadlock detected", "40P01");
    private static final SQLException POSTGRES_SERIALIZATION = new SQLException("could not serialize", "40001");

    /** A clock the test moves, and a sleeper that moves it by the time slept instead of waiting. */
    private static final class FakeTime {
        long nanos;
        final List<Long> sleeps = new ArrayList<>();
        boolean interrupted;

        boolean sleep(long millis) {
            if (interrupted) return false;
            sleeps.add(millis);
            nanos += TimeUnit.MILLISECONDS.toNanos(millis);
            return true;
        }

        WriteRetry.Run run() {
            return new WriteRetry.Run(() -> nanos, this::sleep);
        }
    }

    @Test
    void aMysqlDeadlockIsALockConflictEvenWhenWrapped() {
        assertThat(WriteRetry.isLockConflict(MYSQL_DEADLOCK)).isTrue();
        assertThat(WriteRetry.isLockConflict(new DeadlockLoserDataAccessException("x", MYSQL_DEADLOCK))).isTrue();
    }

    @Test
    void aPostgresDeadlockOrSerializationFailureIsALockConflict() {
        assertThat(WriteRetry.isLockConflict(POSTGRES_DEADLOCK)).isTrue();
        assertThat(WriteRetry.isLockConflict(POSTGRES_SERIALIZATION)).isTrue();
    }

    @Test
    void otherFailuresAreNot() {
        assertThat(WriteRetry.isLockConflict(new SQLException("Duplicate entry", "23000", 1062))).isFalse();
        assertThat(WriteRetry.isLockConflict(new IllegalArgumentException("bad"))).isFalse();
    }

    @Test
    void aLockWaitTimeoutIsNotRetried() {
        assertThat(WriteRetry.isLockConflict(MYSQL_LOCK_TIMEOUT)).isFalse();
        assertThat(new FakeTime().run().retry(MYSQL_LOCK_TIMEOUT)).isFalse();
    }

    @Test
    void aMysqlDeadlockIsRetriedUpToTheDeadlockBound() {
        FakeTime time = new FakeTime();
        WriteRetry.Run run = time.run();
        for (int attempt = 1; attempt < WriteRetry.DEADLOCK_ATTEMPTS; attempt++) {
            assertThat(run.retry(MYSQL_DEADLOCK)).as("retry after attempt %d", attempt).isTrue();
        }
        assertThat(run.retry(MYSQL_DEADLOCK)).isFalse();
        assertThat(time.sleeps).hasSize(WriteRetry.DEADLOCK_ATTEMPTS - 1);
    }

    @Test
    void otherConflictsKeepTheSingleRetry() {
        for (SQLException conflict : new SQLException[] {POSTGRES_DEADLOCK, POSTGRES_SERIALIZATION}) {
            WriteRetry.Run run = new FakeTime().run();
            assertThat(run.retry(conflict)).isTrue();
            assertThat(run.retry(conflict)).isFalse();
        }
    }

    @Test
    void aSerializationStateWithoutMysqlsCodeIsNotAMysqlDeadlock() {
        assertThat(WriteRetry.isMysqlDeadlock(POSTGRES_SERIALIZATION)).isFalse();
        assertThat(WriteRetry.isMysqlDeadlock(MYSQL_DEADLOCK)).isTrue();
    }

    @Test
    void aFailureThatIsNotAConflictIsNeverRetried() {
        assertThat(new FakeTime().run().retry(new SQLException("Duplicate entry", "23000", 1062))).isFalse();
    }

    @Test
    void retriesStopOnceTheTimeBudgetIsSpent() {
        FakeTime time = new FakeTime();
        WriteRetry.Run run = time.run();
        assertThat(run.retry(MYSQL_DEADLOCK)).isTrue();
        time.nanos += WriteRetry.BUDGET.toNanos();
        assertThat(run.retry(MYSQL_DEADLOCK)).isFalse();
    }

    @Test
    void aPauseIsAtMostTheBoundForItsAttemptAndAllPausesStayShort() {
        FakeTime time = new FakeTime();
        WriteRetry.Run run = time.run();
        while (run.retry(MYSQL_DEADLOCK)) {
            // retries until the attempts run out
        }
        assertThat(time.sleeps).allMatch(millis -> millis >= 1);
        assertThat(time.sleeps.stream().mapToLong(Long::longValue).sum()).isLessThanOrEqualTo(310);
    }

    @Test
    void anInterruptedPauseStopsTheRetries() {
        FakeTime time = new FakeTime();
        time.interrupted = true;
        assertThat(time.run().retry(MYSQL_DEADLOCK)).isFalse();
    }
}
