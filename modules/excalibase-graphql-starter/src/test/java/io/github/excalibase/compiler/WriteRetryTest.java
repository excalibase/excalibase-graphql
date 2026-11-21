package io.github.excalibase.compiler;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class WriteRetryTest {

    @Test
    void aMysqlDeadlockOrLockTimeoutIsALockConflictEvenWhenWrapped() {
        assertThat(WriteRetry.isLockConflict(new SQLException("Deadlock found", "40001", 1213))).isTrue();
        assertThat(WriteRetry.isLockConflict(new SQLException("Lock wait timeout", "HY000", 1205))).isTrue();
        assertThat(WriteRetry.isLockConflict(new DeadlockLoserDataAccessException("x",
                new SQLException("Deadlock found", "40001", 1213)))).isTrue();
    }

    @Test
    void aPostgresDeadlockOrSerializationFailureIsALockConflict() {
        assertThat(WriteRetry.isLockConflict(new SQLException("deadlock detected", "40P01"))).isTrue();
        assertThat(WriteRetry.isLockConflict(new SQLException("could not serialize", "40001"))).isTrue();
    }

    @Test
    void otherFailuresAreNot() {
        assertThat(WriteRetry.isLockConflict(new SQLException("Duplicate entry", "23000", 1062))).isFalse();
        assertThat(WriteRetry.isLockConflict(new IllegalArgumentException("bad"))).isFalse();
    }

    @Test
    void aMysqlDeadlockIsRetriedUpToTheDeadlockBound() {
        SQLException deadlock = new SQLException("Deadlock found", "40001", 1213);
        assertThat(WriteRetry.shouldRetry(deadlock, WriteRetry.DEADLOCK_ATTEMPTS - 1)).isTrue();
        assertThat(WriteRetry.shouldRetry(deadlock, WriteRetry.DEADLOCK_ATTEMPTS)).isFalse();
    }

    @Test
    void otherConflictsKeepTheSingleRetry() {
        SQLException timeout = new SQLException("Lock wait timeout", "HY000", 1205);
        SQLException postgres = new SQLException("deadlock detected", "40P01");
        for (SQLException conflict : new SQLException[] {timeout, postgres}) {
            assertThat(WriteRetry.shouldRetry(conflict, 1)).isTrue();
            assertThat(WriteRetry.shouldRetry(conflict, WriteRetry.ATTEMPTS)).isFalse();
        }
    }

    @Test
    void aFailureThatIsNotAConflictIsNeverRetried() {
        assertThat(WriteRetry.shouldRetry(new SQLException("Duplicate entry", "23000", 1062), 1)).isFalse();
    }
}
