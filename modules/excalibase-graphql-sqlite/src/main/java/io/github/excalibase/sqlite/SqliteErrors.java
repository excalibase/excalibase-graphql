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

    private static final Pattern FAILED = Pattern.compile("constraint failed: ([\\w.,\\s]+)");
    private static final Pattern NAMED_CHECK = Pattern.compile("CHECK constraint failed: (\\w+)");

    private SqliteErrors() {
    }

    static Optional<DataError> describe(SQLException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
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

    private static String group(Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
