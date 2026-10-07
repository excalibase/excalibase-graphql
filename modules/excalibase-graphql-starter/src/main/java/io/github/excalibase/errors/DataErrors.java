package io.github.excalibase.errors;

import io.github.excalibase.SqlDialect;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Turns a failed request into the {@link DataError} its client should see, or nothing when the failure is
 * the server's own fault (which the caller logs and answers with 500). Database errors are classified by
 * the dialect, which knows its driver; the generic fallback reads only the SQLSTATE and never repeats the
 * driver's text, which can carry PL/pgSQL context, SQL or values the client did not send.
 */
public final class DataErrors {

    private DataErrors() {
    }

    public static Optional<DataError> describe(Throwable failure, SqlDialect dialect) {
        Optional<DataError> raised = raisedByEngine(failure);
        if (raised.isPresent()) {
            return raised;
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

    private static Optional<DataError> raisedByEngine(Throwable failure) {
        for (Throwable current = failure; current != null; current = next(current)) {
            if (current instanceof DataErrorException refused) {
                return Optional.of(refused.error());
            }
        }
        return Optional.empty();
    }

    private static Optional<SQLException> sqlException(Throwable failure) {
        for (Throwable current = failure; current != null; current = next(current)) {
            if (current instanceof SQLException sql) {
                return Optional.of(sql);
            }
        }
        return Optional.empty();
    }

    private static Throwable next(Throwable current) {
        Throwable cause = current.getCause();
        return cause == current ? null : cause;
    }
}
