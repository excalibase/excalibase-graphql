package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.schema.SchemaInfo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * The SQL type of a column, as far as permission values care: how a value converts to it, how it
 * binds, and whether two values of it can be compared in memory exactly as Postgres compares them.
 */
public final class ColumnType {

    public enum Kind {
        SMALLINT, INTEGER, BIGINT, NUMERIC, REAL, DOUBLE, BOOLEAN, UUID, TEXT, TIMESTAMPTZ, TIMESTAMP, DATE,
        /** A Postgres enum: labels compare equal as text, but order by declaration. */
        ENUM,
        /** Any other type: bound as text and cast by the database, never compared in memory. */
        OTHER
    }

    private static final Map<String, Kind> KINDS = Map.ofEntries(
            entry("smallint", Kind.SMALLINT), entry("int2", Kind.SMALLINT),
            entry("integer", Kind.INTEGER), entry("int", Kind.INTEGER), entry("int4", Kind.INTEGER),
            entry("bigint", Kind.BIGINT), entry("int8", Kind.BIGINT),
            entry("numeric", Kind.NUMERIC), entry("decimal", Kind.NUMERIC),
            entry("real", Kind.REAL), entry("float4", Kind.REAL),
            entry("double precision", Kind.DOUBLE), entry("float8", Kind.DOUBLE),
            entry("boolean", Kind.BOOLEAN), entry("bool", Kind.BOOLEAN),
            entry("uuid", Kind.UUID),
            entry("text", Kind.TEXT), entry("character varying", Kind.TEXT), entry("varchar", Kind.TEXT),
            entry("timestamp with time zone", Kind.TIMESTAMPTZ), entry("timestamptz", Kind.TIMESTAMPTZ),
            entry("timestamp without time zone", Kind.TIMESTAMP), entry("timestamp", Kind.TIMESTAMP),
            entry("date", Kind.DATE));

    private static final Map<Kind, String> BUILT_IN_NAMES = Map.ofEntries(
            entry(Kind.SMALLINT, "smallint"), entry(Kind.INTEGER, "integer"), entry(Kind.BIGINT, "bigint"),
            entry(Kind.NUMERIC, "numeric"), entry(Kind.REAL, "real"), entry(Kind.DOUBLE, "double precision"),
            entry(Kind.BOOLEAN, "boolean"), entry(Kind.UUID, "uuid"), entry(Kind.TEXT, "text"),
            entry(Kind.TIMESTAMPTZ, "timestamptz"), entry(Kind.TIMESTAMP, "timestamp"), entry(Kind.DATE, "date"));

    /** Text ordering depends on the database collation, so only the database may decide it. */
    private static final Set<Kind> UNORDERED_IN_MEMORY = Set.of(Kind.TEXT, Kind.ENUM, Kind.OTHER);

    private static final Pattern CASTABLE = Pattern.compile("^[a-z_][a-z0-9_ ]{0,62}$");

    private final Kind kind;
    private final String castTarget;

    private ColumnType(Kind kind, String castTarget) {
        this.kind = kind;
        this.castTarget = castTarget;
    }

    /** The type of {@code column} in {@code tableKey}; an unknown table or column is refused. */
    public static ColumnType of(SchemaInfo schema, String tableKey, String column) {
        TableRef.resolve(schema, tableKey);
        String sqlType = schema.getColumnType(tableKey, column);
        if (sqlType == null) {
            throw new PermissionEvaluationException(PermissionEvaluationException.UNKNOWN_COLUMN,
                    "Unknown column " + column + " on " + tableKey);
        }
        String userType = schema.getEnumType(tableKey, column);
        if (userType != null) {
            Kind kind = schema.getEnumTypes().containsKey(userType) ? Kind.ENUM : Kind.OTHER;
            return new ColumnType(kind, SqlNames.qualified(userType));
        }
        return builtIn(sqlType);
    }

    private static ColumnType builtIn(String sqlType) {
        String normalized = sqlType.toLowerCase(Locale.ROOT).replaceAll("\\(.*\\)", "").trim();
        Kind kind = KINDS.get(normalized);
        if (kind != null) {
            return new ColumnType(kind, null);
        }
        if (!CASTABLE.matcher(normalized).matches()) {
            throw new PermissionEvaluationException(PermissionEvaluationException.UNSUPPORTED_COLUMN_TYPE,
                    "Unsupported column type " + sqlType);
        }
        return new ColumnType(Kind.OTHER, normalized);
    }

    public Kind kind() {
        return kind;
    }

    /**
     * {@code value} (a JDBC value, a change-feed value or a permission value) as this type's Java
     * value; anything that is not a valid value of the type is refused with IllegalArgumentException.
     */
    Object canonical(Object value) {
        return ColumnValues.convert(kind, value);
    }

    /** The canonical value as a JDBC bind value. */
    Object sqlValue(Object canonical) {
        return canonical instanceof Instant instant ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC) : canonical;
    }

    /** Where a parameter goes in SQL; types bound as text are cast to the column's type. */
    String placeholder(String paramName) {
        return castTarget == null ? ":" + paramName : "CAST(:" + paramName + " AS " + castTarget + ")";
    }

    /** A parameter cast to this type whatever it binds as, so a row of them is typed even when a value is null. */
    String typedPlaceholder(String paramName) {
        String target = castTarget == null ? BUILT_IN_NAMES.get(kind) : castTarget;
        return "CAST(:" + paramName + " AS " + target + ")";
    }

    boolean textual() {
        return kind == Kind.TEXT;
    }

    boolean equatableInMemory() {
        return kind != Kind.OTHER;
    }

    boolean orderedInMemory() {
        return !UNORDERED_IN_MEMORY.contains(kind);
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
