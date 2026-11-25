package io.github.excalibase.sqlite;

import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrors;

import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQLite errors a client can act on, read from the extended result code: its messages name the table and column
 * the constraint is on, which the client sent, and nothing else.
 */
final class SqliteErrors {

    /** sqlite-jdbc reports the primary code (19) as the error code; the extended one leads the message. */
    private static final String UNIQUE = "SQLITE_CONSTRAINT_UNIQUE";
    private static final String PRIMARY_KEY = "SQLITE_CONSTRAINT_PRIMARYKEY";
    private static final String FOREIGN_KEY = "SQLITE_CONSTRAINT_FOREIGNKEY";
    private static final String NOT_NULL = "SQLITE_CONSTRAINT_NOTNULL";
    private static final String CHECK = "SQLITE_CONSTRAINT_CHECK";

    private static final String BUSY = "SQLITE_BUSY";
    private static final String LOCKED = "SQLITE_LOCKED";
    private static final String INTERRUPT = "SQLITE_INTERRUPT";
    private static final int UNAVAILABLE = 503;
    private static final int PRIMARY_CODE_MASK = 0xFF;
    private static final int CODE_BUSY = 5;
    private static final int CODE_LOCKED = 6;
    private static final int CODE_INTERRUPT = 9;

    private static final Pattern FAILED = Pattern.compile("constraint failed: ([\\w.,\\s]+)");
    private static final Pattern NAMED_CHECK = Pattern.compile("CHECK constraint failed: (\\w+)");

    private SqliteErrors() {
    }

    static Optional<DataError> describe(SQLException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        Optional<DataError> unavailable = unavailable(failure, message);
        if (unavailable.isPresent()) {
            return unavailable;
        }
        if (message.contains(UNIQUE) || message.contains(PRIMARY_KEY)) {
            String key = group(FAILED, message);
            return Optional.of(DataError.conflict(DataError.UNIQUE_VIOLATION,
                    "Duplicate value violates unique constraint" + (key == null ? "" : " '" + key + "'"), key));
        }
        if (message.contains(FOREIGN_KEY)) {
            return Optional.of(DataError.badRequest(DataError.FOREIGN_KEY_VIOLATION,
                    "Value violates a foreign key constraint", null, null));
        }
        if (message.contains(NOT_NULL)) {
            String column = group(FAILED, message);
            return Optional.of(DataError.badRequest(DataError.NOT_NULL_VIOLATION,
                    "Null value in column" + (column == null ? "" : " '" + column + "'") + " violates not-null constraint",
                    null, column == null ? null : column.substring(column.lastIndexOf('.') + 1)));
        }
        if (message.contains(CHECK)) {
            return Optional.of(DataError.badRequest(DataError.CHECK_VIOLATION, "Value violates a check constraint",
                    group(NAMED_CHECK, message), null));
        }
        return DataErrors.fromSqlState(failure);
    }

    /** A busy file or a stopped statement is the engine's to retry or bound, not the client's mistake. */
    private static Optional<DataError> unavailable(SQLException failure, String message) {
        int code = failure.getErrorCode() & PRIMARY_CODE_MASK;
        if (code == CODE_BUSY || code == CODE_LOCKED || message.contains(BUSY) || message.contains(LOCKED)) {
            return Optional.of(new DataError(UNAVAILABLE, "database_busy",
                    "The database is busy with another write; retry the request", null, null));
        }
        if (code == CODE_INTERRUPT || message.contains(INTERRUPT)) {
            return Optional.of(new DataError(UNAVAILABLE, "statement_timeout",
                    "The statement ran longer than the engine allows and was stopped", null, null));
        }
        return Optional.empty();
    }

    private static String group(Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
