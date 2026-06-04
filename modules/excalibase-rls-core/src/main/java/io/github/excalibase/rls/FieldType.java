package io.github.excalibase.rls;

public enum FieldType {
    STRING,
    UUID,
    INTEGER,
    LONG,
    BOOLEAN,
    DOUBLE,
    /** Exact decimal — coerces to {@link java.math.BigDecimal} so NUMERIC
     *  comparisons bind without the precision loss of {@link #DOUBLE}. */
    DECIMAL,
    DATE,
    DATETIME
}
