package io.github.excalibase.compiler;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Set;

/**
 * A number a client sent as a string, bound as the number its column holds. Reads answer bigint as a
 * string (JSON numbers lose precision past 2^53), so the same string must be accepted back by every
 * filter and write; bound as text it would not compare with, or be assigned to, a numeric column.
 */
public final class NumericInput {

    private static final Set<String> INTEGERS = Set.of("smallint", "integer", "bigint", "int", "int2", "int4",
            "int8", "serial", "smallserial", "bigserial");
    private static final Set<String> DECIMALS = Set.of("numeric", "decimal", "real", "double precision",
            "float4", "float8");

    private NumericInput() {
    }

    /** {@code value} as a number when it is a numeric string for a numeric {@code columnType}, else unchanged. */
    public static Object coerce(String columnType, Object value) {
        if (columnType == null || !(value instanceof String text)) {
            return value;
        }
        String type = columnType.toLowerCase(Locale.ROOT);
        String number = text.trim();
        try {
            if (INTEGERS.contains(type)) {
                BigInteger integer = new BigInteger(number.startsWith("+") ? number.substring(1) : number);
                return integer.bitLength() < Long.SIZE ? integer.longValue() : integer;
            }
            if (DECIMALS.contains(type)) {
                return new BigDecimal(number);
            }
        } catch (NumberFormatException _) {
            // Not a number: the database refuses it with its own reason.
        }
        return value;
    }
}
