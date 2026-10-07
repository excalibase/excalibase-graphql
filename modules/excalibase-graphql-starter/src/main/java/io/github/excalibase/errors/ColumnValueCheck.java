package io.github.excalibase.errors;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Refuses a filter value its column's type cannot hold before the query runs, so the client hears which
 * column was wrong instead of a database error. Only string values of uuid and number columns are judged;
 * anything else (temporal input is too varied to judge safely) is left to the database.
 */
public final class ColumnValueCheck {

    private static final Set<String> INTEGERS = Set.of("smallint", "integer", "bigint", "int", "int2", "int4",
            "int8", "mediumint", "serial", "smallserial", "bigserial");
    private static final Set<String> DECIMALS = Set.of("numeric", "decimal", "real", "double precision", "double",
            "float", "float4", "float8");
    private static final Set<String> SPECIAL_FLOATS = Set.of("nan", "infinity", "+infinity", "-infinity", "inf",
            "+inf", "-inf");
    private static final Pattern UUID_HEX = Pattern.compile("[0-9a-fA-F]{32}");

    private ColumnValueCheck() {
    }

    /** @throws DataErrorException {@code invalid_value} naming {@code column} when {@code value} cannot be its type */
    public static void require(String table, String column, String type, Object value) {
        if (type == null || !(value instanceof String text)) {
            return;
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        String expected = expectedType(normalized, text.trim());
        if (expected != null) {
            throw new DataErrorException(DataError.invalidValue(column, "Invalid value for column '" + column
                    + "' of " + table + ": \"" + text + "\" is not " + expected));
        }
    }

    /** The kind of value {@code type} needs when {@code text} is not one, else null. */
    private static String expectedType(String type, String text) {
        if ("uuid".equals(type)) {
            return isUuid(text) ? null : "a uuid";
        }
        if (INTEGERS.contains(type)) {
            return isInteger(text) ? null : "an integer";
        }
        if (DECIMALS.contains(type)) {
            return isDecimal(text) ? null : "a number";
        }
        return null;
    }

    /** Postgres accepts upper or lower case, optional braces and hyphens between groups of digits. */
    private static boolean isUuid(String text) {
        String bare = text.startsWith("{") && text.endsWith("}") ? text.substring(1, text.length() - 1) : text;
        return UUID_HEX.matcher(bare.replace("-", "")).matches();
    }

    private static boolean isInteger(String text) {
        try {
            new BigInteger(text.startsWith("+") ? text.substring(1) : text);
            return true;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    private static boolean isDecimal(String text) {
        if (SPECIAL_FLOATS.contains(text.toLowerCase(Locale.ROOT))) {
            return true;
        }
        try {
            new BigDecimal(text);
            return true;
        } catch (NumberFormatException _) {
            return false;
        }
    }
}
