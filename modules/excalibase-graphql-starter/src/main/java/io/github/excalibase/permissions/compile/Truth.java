package io.github.excalibase.permissions.compile;

/**
 * SQL three-valued logic plus {@link #NEEDS_DATABASE}: a value only the database can supply.
 * A decided operand still decides ({@code FALSE AND ?} is false); otherwise the database is asked.
 */
enum Truth {
    TRUE, FALSE, UNKNOWN, NEEDS_DATABASE;

    static Truth of(boolean value) {
        return value ? TRUE : FALSE;
    }

    Truth and(Truth other) {
        if (this == FALSE || other == FALSE) {
            return FALSE;
        }
        return weakest(other, TRUE);
    }

    Truth or(Truth other) {
        if (this == TRUE || other == TRUE) {
            return TRUE;
        }
        return weakest(other, FALSE);
    }

    Truth not() {
        return switch (this) {
            case TRUE -> FALSE;
            case FALSE -> TRUE;
            default -> this;
        };
    }

    private Truth weakest(Truth other, Truth identity) {
        if (this == NEEDS_DATABASE || other == NEEDS_DATABASE) {
            return NEEDS_DATABASE;
        }
        return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : identity;
    }
}
