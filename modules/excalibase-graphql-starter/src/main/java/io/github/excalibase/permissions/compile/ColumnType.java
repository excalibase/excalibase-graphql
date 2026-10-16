package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.schema.SchemaInfo;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The SQL type of a column, as far as permission values care: how a value converts to it, how it
 * binds, and whether two values of it can be compared in memory exactly as the database compares them.
 */
public final class ColumnType {

    public enum Kind {
        SMALLINT, INTEGER, BIGINT, NUMERIC, REAL, DOUBLE, BOOLEAN, UUID, TEXT, TIMESTAMPTZ, TIMESTAMP, DATE,
        /** An enum: labels compare equal as text, but order by declaration. */
        ENUM,
        /** Any other type: bound as text and cast by the database, never compared in memory. */
        OTHER
    }

    /** Text ordering depends on the database collation, so only the database may decide it. */
    private static final Set<Kind> UNORDERED_IN_MEMORY = Set.of(Kind.TEXT, Kind.ENUM, Kind.OTHER);

    private final PermissionDialect dialect;
    private final Kind kind;
    private final String castTarget;
    private final List<String> labels;
    private final String charset;
    private final String collation;
    private final boolean comparable;

    private ColumnType(PermissionDialect dialect, Kind kind, String castTarget, List<String> labels, String charset,
                       String collation, boolean comparable) {
        this.dialect = dialect;
        this.kind = kind;
        this.castTarget = castTarget;
        this.labels = labels;
        this.charset = charset;
        this.collation = collation;
        this.comparable = comparable;
    }

    /** A type the database casts a text parameter to, or a built-in one when {@code castTarget} is null. */
    static ColumnType cast(PermissionDialect dialect, Kind kind, String castTarget) {
        return new ColumnType(dialect, kind, castTarget, List.of(), null, null, true);
    }

    /** A type whose text compares under {@code collation}; an enum lists its labels in declaration order. */
    static ColumnType collated(PermissionDialect dialect, Kind kind, List<String> labels, String charset,
                               String collation) {
        return new ColumnType(dialect, kind, null, List.copyOf(labels), charset, collation, true);
    }

    /** A type the dialect has no mapping for: a column of it can only be tested for NULL. */
    static ColumnType unsupported(PermissionDialect dialect) {
        return new ColumnType(dialect, Kind.OTHER, null, List.of(), null, null, false);
    }

    /** The type of {@code column} in {@code tableKey}; an unknown table or column is refused. */
    public static ColumnType of(PermissionDialect dialect, SchemaInfo schema, String tableKey, String column) {
        TableRef.resolve(dialect, schema, tableKey);
        String sqlType = schema.getColumnType(tableKey, column);
        if (sqlType == null) {
            throw new PermissionEvaluationException(PermissionEvaluationException.UNKNOWN_COLUMN,
                    "Unknown column " + column + " on " + tableKey);
        }
        return dialect.sql().columnType(schema, tableKey, column, sqlType);
    }

    public Kind kind() {
        return kind;
    }

    String castTarget() {
        return castTarget;
    }

    List<String> labels() {
        return labels;
    }

    String charset() {
        return charset;
    }

    String collation() {
        return collation;
    }

    /**
     * {@code value} (a JDBC value, a change-feed value or a permission value) as this type's Java
     * value; anything that is not a valid value of the type is refused with IllegalArgumentException.
     */
    Object canonical(Object value) {
        return dialect.sql().canonical(this, value);
    }

    /** The canonical value as a JDBC bind value compared with the column. */
    Object sqlValue(Object canonical) {
        return dialect.sql().compareValue(this, canonical);
    }

    /** The canonical value as a JDBC bind value written into the column. */
    Object writeValue(Object canonical) {
        return dialect.sql().writeValue(this, canonical);
    }

    /** Where a parameter goes in SQL; a column of a type with no mapping cannot be compared. */
    String placeholder(String paramName) {
        requireComparable();
        return dialect.sql().placeholder(this, paramName);
    }

    /** A parameter typed as this column whatever it binds as, so a row of them is typed even when a value is null. */
    Optional<String> typedPlaceholder(String paramName) {
        return comparable ? dialect.sql().imagePlaceholder(this, paramName) : Optional.empty();
    }

    /** {@code reference} as comparisons read it; {@code image} when it names a value of an image row. */
    String compared(String reference, boolean image) {
        return dialect.sql().compared(this, reference, image);
    }

    void requireComparable() {
        if (!comparable) {
            throw new PermissionEvaluationException(PermissionEvaluationException.UNSUPPORTED_COLUMN_TYPE,
                    "A column of this type can only be tested with _is_null");
        }
    }

    boolean textual() {
        return kind == Kind.TEXT;
    }

    boolean equatableInMemory() {
        return comparable && kind != Kind.OTHER && dialect.sql().equatableInMemory(this);
    }

    boolean orderedInMemory() {
        return equatableInMemory() && !UNORDERED_IN_MEMORY.contains(kind);
    }

    /** Postgres order of two canonical values of this type. */
    @SuppressWarnings("unchecked")
    int compare(Object left, Object right) {
        if (left instanceof UUID a && right instanceof UUID b) {
            int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
            return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
        }
        if (left instanceof Number a && right instanceof Number b && (kind == Kind.REAL || kind == Kind.DOUBLE)) {
            return floating(a.doubleValue(), b.doubleValue());
        }
        return ((Comparable<Object>) left).compareTo(right);
    }

    /** Postgres float order: -0 equals 0, NaN equals NaN and sorts above every number. */
    private static int floating(double left, double right) {
        if (left == right) {
            return 0;
        }
        return Double.compare(left, right);
    }
}
