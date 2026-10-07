package io.github.excalibase.postgres;

import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrors;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Postgres errors a client can act on, read from the server's structured fields. Only the primary message
 * is answered: never the Where context (PL/pgSQL function and line), the Detail, the file or the query.
 */
final class PostgresErrors {

    private PostgresErrors() {
    }

    static Optional<DataError> describe(SQLException failure) {
        ServerErrorMessage server = failure instanceof PSQLException psql ? psql.getServerErrorMessage() : null;
        String state = failure.getSQLState();
        if (server == null || server.getMessage() == null || state == null) {
            return DataErrors.fromSqlState(failure);
        }
        String message = server.getMessage();
        String constraint = server.getConstraint();
        return Optional.ofNullable(switch (state) {
            case "23505" -> DataError.conflict(DataError.UNIQUE_VIOLATION, message, constraint);
            case "23503" -> DataError.badRequest(DataError.FOREIGN_KEY_VIOLATION, message, constraint, null);
            case "23514" -> DataError.badRequest(DataError.CHECK_VIOLATION, message, constraint, null);
            case "23502" -> DataError.badRequest(DataError.NOT_NULL_VIOLATION, message, null, server.getColumn());
            case "23P01" -> DataError.badRequest(DataError.EXCLUSION_VIOLATION, message, constraint, null);
            case "P0001" -> DataError.badRequest(DataError.RAISED_EXCEPTION, message);
            case "42501" -> DataError.forbidden(message, null);
            default -> byClass(state, message, constraint, server.getColumn());
        });
    }

    private static DataError byClass(String state, String message, String constraint, String column) {
        if (state.startsWith("23")) {
            return DataError.badRequest(DataError.CONSTRAINT_VIOLATION, message, constraint, column);
        }
        if (state.startsWith("22")) {
            return DataError.badRequest(DataError.INVALID_VALUE, message, null, column);
        }
        return null;
    }
}
