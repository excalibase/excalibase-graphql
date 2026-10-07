package io.github.excalibase.mysql;

import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrors;

import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL errors a client can act on, read from the server's error number. MySQL reports duplicate keys and
 * foreign keys under one SQLSTATE (23000) and names the schema and the database user in some messages, so
 * those messages are rebuilt from the parts that are safe to answer.
 */
final class MysqlErrors {

    private static final int DUPLICATE_ENTRY = 1062;
    private static final int ROW_IS_REFERENCED = 1451;
    private static final int NO_REFERENCED_ROW = 1452;
    private static final int CHECK_VIOLATED = 3819;
    private static final int COLUMN_CANNOT_BE_NULL = 1048;
    private static final int NO_DEFAULT_VALUE = 1364;
    private static final int SIGNAL = 1644;
    private static final int TABLE_ACCESS_DENIED = 1142;
    private static final int COLUMN_ACCESS_DENIED = 1143;
    private static final int TRUNCATED_WRONG_VALUE = 1292;
    private static final int INCORRECT_VALUE = 1366;
    private static final int DATA_TRUNCATED = 1265;
    private static final int DATA_TOO_LONG = 1406;
    private static final int OUT_OF_RANGE = 1264;

    private static final Pattern KEY = Pattern.compile("for key '(?:[^'.]*\\.)?([^']*)'");
    private static final Pattern FOREIGN_KEY = Pattern.compile("CONSTRAINT `([^`]*)`");
    private static final Pattern QUOTED_CONSTRAINT = Pattern.compile("constraint '([^']*)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN = Pattern.compile("(?:Column|Field|column) '([^']*)'");

    private MysqlErrors() {
    }

    static Optional<DataError> describe(SQLException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        return Optional.ofNullable(switch (failure.getErrorCode()) {
            case DUPLICATE_ENTRY -> {
                String key = group(KEY, message);
                yield DataError.conflict(DataError.UNIQUE_VIOLATION,
                        "Duplicate value violates unique constraint '" + key + "'", key);
            }
            case ROW_IS_REFERENCED, NO_REFERENCED_ROW -> {
                String constraint = group(FOREIGN_KEY, message);
                yield DataError.badRequest(DataError.FOREIGN_KEY_VIOLATION,
                        "Value violates foreign key constraint '" + constraint + "'", constraint, null);
            }
            case CHECK_VIOLATED -> DataError.badRequest(DataError.CHECK_VIOLATION, message,
                    group(QUOTED_CONSTRAINT, message), null);
            case COLUMN_CANNOT_BE_NULL, NO_DEFAULT_VALUE -> DataError.badRequest(DataError.NOT_NULL_VIOLATION,
                    message, null, group(COLUMN, message));
            case SIGNAL -> DataError.badRequest(DataError.RAISED_EXCEPTION, message);
            case TABLE_ACCESS_DENIED, COLUMN_ACCESS_DENIED -> DataError.forbidden("Permission denied", null);
            case TRUNCATED_WRONG_VALUE, INCORRECT_VALUE, DATA_TRUNCATED, DATA_TOO_LONG, OUT_OF_RANGE ->
                    DataError.badRequest(DataError.INVALID_VALUE, message, null, group(COLUMN, message));
            default -> DataErrors.fromSqlState(failure).orElse(null);
        });
    }

    private static String group(Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }
}
