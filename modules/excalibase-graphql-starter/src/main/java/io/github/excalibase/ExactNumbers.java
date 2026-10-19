package io.github.excalibase;

import java.util.Locale;
import java.util.Set;

/**
 * The column types the API answers as strings holding every digit, in GraphQL and REST alike: bigints and
 * decimals. A JSON number past 2^53, or with more than about 15 significant digits, is rounded by JavaScript's
 * parser, so these are never sent as one. Inputs take either a string or a number.
 */
public final class ExactNumbers {

    private static final Set<String> INTEGERS = Set.of("bigint", "int8", "bigserial", "serial8");
    private static final Set<String> DECIMALS = Set.of("numeric", "decimal");

    private ExactNumbers() {
    }

    /** True for a bigint column (answered as a string of its digits). */
    public static boolean isBigInteger(String columnType) {
        return columnType != null && INTEGERS.contains(columnType.toLowerCase(Locale.ROOT));
    }

    /** True for a numeric or decimal column (answered as a string with its scale). */
    public static boolean isBigDecimal(String columnType) {
        return columnType != null && DECIMALS.contains(columnType.toLowerCase(Locale.ROOT));
    }

    /** True for a column answered as a string of digits. */
    public static boolean isExact(String columnType) {
        return isBigInteger(columnType) || isBigDecimal(columnType);
    }

    /** True for an array column whose elements are answered as strings of digits. */
    public static boolean isExactArray(String columnType) {
        return ArrayColumns.isArray(columnType) && isExact(ArrayColumns.elementType(columnType));
    }
}
