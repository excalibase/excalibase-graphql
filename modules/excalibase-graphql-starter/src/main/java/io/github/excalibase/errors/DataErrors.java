package io.github.excalibase.errors;

import io.github.excalibase.SqlDialect;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a failed request into the {@link DataError} its client should see, or nothing when the failure is
 * the server's own fault (which the caller logs and answers with 500). Database errors are classified by
 * the dialect, which knows its driver; the generic fallback reads only the SQLSTATE and never repeats the
 * driver's text, which can carry PL/pgSQL context, SQL or values the client did not send.
 */
public final class DataErrors {

    private static final Set<String> UNREACHABLE_STATES =
            Set.of("08001", "08003", "08004", "08006", "08S01", "57P01", "57P03");

    private DataErrors() {
    }

    public static Optional<DataError> describe(Throwable failure, SqlDialect dialect) {
        Optional<DataError> raised = raisedByEngine(failure);
        if (raised.isPresent()) {
            return raised;
        }
        if (commitOutcomeUnknown(failure)) {
            return Optional.of(DataError.commitUnknown());
        }
        if (databaseUnreachable(failure)) {
            return Optional.of(DataError.projectUnavailable());
        }
        return sqlException(failure).flatMap(sql -> dialect != null ? dialect.describeError(sql) : fromSqlState(sql));
    }

    /** The SQLSTATE classes every database shares: integrity (23) and data (22) failures. */
    public static Optional<DataError> fromSqlState(SQLException sql) {
        String state = sql.getSQLState();
        if (state == null || state.length() != 5) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (state) {
            case "23505" -> DataError.conflict(DataError.UNIQUE_VIOLATION,
                    "Duplicate value violates a unique constraint", null);
            case "23503" -> DataError.badRequest(DataError.FOREIGN_KEY_VIOLATION,
                    "Value violates a foreign key constraint");
            case "23514" -> DataError.badRequest(DataError.CHECK_VIOLATION, "Value violates a check constraint");
            case "23502" -> DataError.badRequest(DataError.NOT_NULL_VIOLATION, "Value violates a not-null constraint");
            case "23P01" -> DataError.badRequest(DataError.EXCLUSION_VIOLATION,
                    "Value violates an exclusion constraint");
            default -> byClass(state);
        });
    }

    private static DataError byClass(String state) {
        if (state.startsWith("23")) {
            return DataError.badRequest(DataError.CONSTRAINT_VIOLATION, "Value violates a constraint");
        }
        if (state.startsWith("22")) {
            return DataError.badRequest(DataError.INVALID_VALUE, "Invalid value");
        }
        return null;
    }

    /**
     * The database could not be reached: a pooled connection that could not be had in time, or a connection that
     * was refused (08001, 08004), lost (08003, 08006, MySQL 08S01) or is going away (57P01, 57P03). Other class 08
     * states, such as a protocol violation by the client's own request, are not an outage.
     */
    private static boolean databaseUnreachable(Throwable failure) {
        for (Throwable current : chain(failure)) {
            if (isConnectionLoss(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConnectionLoss(Throwable failure) {
        return failure instanceof SQLTransientConnectionException
                || failure instanceof SQLException sql && sql.getSQLState() != null
                        && UNREACHABLE_STATES.contains(sql.getSQLState());
    }

    /**
     * The connection was lost while committing (SQLSTATE 08007, or a failed commit whose cause is a lost connection):
     * the write may have been applied, so the client must not retry it blindly. A failed rollback, or a commit refused
     * for a constraint, is not this: nothing was kept.
     */
    private static boolean commitOutcomeUnknown(Throwable failure) {
        boolean commitFailed = false;
        boolean connectionLost = false;
        for (Throwable current : chain(failure)) {
            if (current instanceof SQLException sql && "08007".equals(sql.getSQLState())) {
                return true;
            }
            commitFailed |= isCommitFailure(current);
            connectionLost |= isConnectionLoss(current);
        }
        return commitFailed && connectionLost;
    }

    /** Spring's own words for a commit that failed: {@code JDBC commit; ...} (JDBC transaction manager) or {@code Could not commit ...}. */
    private static boolean isCommitFailure(Throwable failure) {
        String message = failure.getMessage();
        return message != null && (failure instanceof DataAccessException || failure instanceof TransactionException)
                && (message.startsWith("JDBC commit") || message.startsWith("Could not commit"));
    }

    private static Optional<DataError> raisedByEngine(Throwable failure) {
        for (Throwable current : chain(failure)) {
            if (current instanceof DataErrorException refused) {
                return Optional.of(refused.error());
            }
        }
        return Optional.empty();
    }

    private static Optional<SQLException> sqlException(Throwable failure) {
        for (Throwable current : chain(failure)) {
            if (current instanceof SQLException sql) {
                return Optional.of(sql);
            }
        }
        return Optional.empty();
    }

    /**
     * The failure and everything behind it: its causes, and for a transaction failure that hid the original behind a
     * failed rollback, the exception the application raised.
     */
    private static void addIfPresent(Deque<Throwable> pending, Throwable failure) {
        if (failure != null) {
            pending.add(failure);
        }
    }

    private static List<Throwable> chain(Throwable failure) {
        List<Throwable> seen = new ArrayList<>();
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.poll();
            if (seen.stream().anyMatch(known -> known == current)) {
                continue;
            }
            seen.add(current);
            addIfPresent(pending, current.getCause());
            if (current instanceof TransactionSystemException system) {
                addIfPresent(pending, system.getApplicationException());
            }
        }
        return seen;
    }
}
