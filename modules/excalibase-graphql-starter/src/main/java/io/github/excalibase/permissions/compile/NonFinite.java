package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.ComparisonOperator;
import io.github.excalibase.permissions.compile.ColumnType.Kind;

/**
 * NaN and the infinities as compared values where a float column cannot hold them (MySQL). Postgres
 * orders {@code -Infinity} below every number and {@code Infinity} and {@code NaN} above, so each
 * comparison is decided for every stored number; a NULL column stays unknown, as in Postgres.
 */
enum NonFinite {
    BELOW_ALL, ABOVE_ALL;

    /** Null unless {@code value} is non-finite and the dialect cannot store it in {@code type}. */
    static NonFinite of(PermissionDialect dialect, ColumnType type, Object value) {
        if (dialect.sql().storesNonFiniteFloats() || (type.kind() != Kind.REAL && type.kind() != Kind.DOUBLE)
                || !(value instanceof Number number)) {
            return null;
        }
        double floating = number.doubleValue();
        if (Double.isNaN(floating) || floating == Double.POSITIVE_INFINITY) {
            return ABOVE_ALL;
        }
        return floating == Double.NEGATIVE_INFINITY ? BELOW_ALL : null;
    }

    String comparedWith(ComparisonOperator operator, String column) {
        boolean holds = switch (operator) {
            case EQ -> false;
            case NEQ -> true;
            case LT, LTE -> this == ABOVE_ALL;
            case GT, GTE -> this == BELOW_ALL;
            default -> throw new IllegalArgumentException("Not a scalar comparison: " + operator);
        };
        return everyStoredValue(holds, column);
    }

    /** An {@code _in} or {@code _nin} whose every value is one no stored number equals. */
    static String noneEqual(boolean positive, String column) {
        return everyStoredValue(!positive, column);
    }

    private static String everyStoredValue(boolean holds, String column) {
        return "(" + column + (holds ? " = " : " <> ") + column + ")";
    }
}
