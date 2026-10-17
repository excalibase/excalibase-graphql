package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.permissions.compile.ColumnType.Kind;
import io.github.excalibase.schema.SchemaInfo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/** Postgres: double-quoted identifiers, parameters cast to the column's type, a typed VALUES row for images. */
final class PostgresSql implements DialectSql {

    private static final Pattern ALIAS = Pattern.compile("^(?:[A-Za-z_]\\w{0,62}|\"\\w{1,63}\")$");

    private static final Pattern CASTABLE = Pattern.compile("^[a-z_][a-z0-9_ ]{0,62}$");

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

    @Override
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public boolean safeAlias(String alias) {
        return ALIAS.matcher(alias).matches();
    }

    @Override
    public ColumnType columnType(SchemaInfo schema, String tableKey, String column, String sqlType) {
        String userType = schema.getEnumType(tableKey, column);
        if (userType != null) {
            Kind kind = schema.getEnumTypes().containsKey(userType) ? Kind.ENUM : Kind.OTHER;
            return ColumnType.cast(PermissionDialect.POSTGRES, kind, qualified(userType));
        }
        String normalized = sqlType.toLowerCase(Locale.ROOT).replaceAll("\\(.*\\)", "").trim();
        Kind kind = KINDS.get(normalized);
        if (kind != null) {
            return ColumnType.cast(PermissionDialect.POSTGRES, kind, null);
        }
        if (!CASTABLE.matcher(normalized).matches()) {
            throw new PermissionEvaluationException(PermissionEvaluationException.UNSUPPORTED_COLUMN_TYPE,
                    "Unsupported column type " + sqlType);
        }
        return ColumnType.cast(PermissionDialect.POSTGRES, Kind.OTHER, normalized);
    }

    /** {@code schema.name} as {@code "schema"."name"}; a bare name is quoted alone. */
    private String qualified(String name) {
        int dot = name.indexOf('.');
        return dot < 0 ? quote(name) : quote(name.substring(0, dot)) + "." + quote(name.substring(dot + 1));
    }

    @Override
    public Object canonical(ColumnType type, Object value) {
        return ColumnValues.convert(type.kind(), value);
    }

    @Override
    public Object compareValue(ColumnType type, Object canonical) {
        return canonical instanceof Instant instant ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC) : canonical;
    }

    @Override
    public Object writeValue(ColumnType type, Object canonical) {
        return compareValue(type, canonical);
    }

    @Override
    public String placeholder(ColumnType type, String param) {
        return type.castTarget() == null ? ":" + param : "CAST(:" + param + " AS " + type.castTarget() + ")";
    }

    @Override
    public Optional<String> imagePlaceholder(ColumnType type, String param) {
        String target = type.castTarget() == null ? BUILT_IN_NAMES.get(type.kind()) : type.castTarget();
        return Optional.of("CAST(:" + param + " AS " + target + ")");
    }

    @Override
    public String compared(ColumnType type, String reference, boolean image) {
        return reference;
    }

    @Override
    public String imageRow(String alias, List<String> placeholders, List<String> names) {
        return " FROM (VALUES (" + String.join(", ", placeholders) + ")) AS " + alias + "(" + String.join(", ", names) + ")";
    }

    @Override
    public boolean equatableInMemory(ColumnType type) {
        return true;
    }

    @Override
    public boolean storesNonFiniteFloats() {
        return true;
    }

    @Override
    public boolean readsWrittenTableThroughDerivedTable() {
        return false;
    }

    @Override
    public boolean tracksFunctions() {
        return true;
    }
}
