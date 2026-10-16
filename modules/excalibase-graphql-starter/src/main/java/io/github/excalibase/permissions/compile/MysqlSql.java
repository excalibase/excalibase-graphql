package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.compile.ColumnType.Kind;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.SchemaInfo.ColumnDeclaration;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * MySQL 8.4: backtick identifiers, a type table read from the column's declaration, timestamps compared
 * as UTC whatever the session time zone, enums by declaration order, text left to the column's
 * collation, and {@code VALUES ROW(...)} image rows.
 */
final class MysqlSql implements DialectSql {

    private static final Pattern ALIAS = Pattern.compile("^(?:[A-Za-z_][A-Za-z0-9_]{0,62}|`[A-Za-z0-9_]{1,63}`)$");

    /** Character set and collation names are spliced into image rows, so only catalog-shaped ones are. */
    private static final Pattern CATALOG_NAME = Pattern.compile("^[a-z0-9_]{1,64}$");

    private static final DateTimeFormatter WRITTEN_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSxxx");

    private static final Map<String, Kind> SIGNED = Map.ofEntries(
            entry("tinyint", Kind.SMALLINT), entry("smallint", Kind.SMALLINT), entry("mediumint", Kind.INTEGER),
            entry("int", Kind.INTEGER), entry("integer", Kind.INTEGER), entry("bigint", Kind.BIGINT),
            entry("decimal", Kind.NUMERIC), entry("numeric", Kind.NUMERIC), entry("float", Kind.REAL),
            entry("double", Kind.DOUBLE), entry("double precision", Kind.DOUBLE), entry("real", Kind.DOUBLE),
            entry("char", Kind.TEXT), entry("varchar", Kind.TEXT), entry("tinytext", Kind.TEXT),
            entry("text", Kind.TEXT), entry("mediumtext", Kind.TEXT), entry("longtext", Kind.TEXT),
            entry("enum", Kind.ENUM), entry("date", Kind.DATE), entry("datetime", Kind.TIMESTAMP),
            entry("timestamp", Kind.TIMESTAMPTZ));

    /** Unsigned integers widen to the next kind that holds their whole range. */
    private static final Map<String, Kind> UNSIGNED = Map.of(
            "smallint", Kind.INTEGER, "int", Kind.BIGINT, "integer", Kind.BIGINT, "bigint", Kind.NUMERIC);

    private static final Map<Kind, String> CASTS = Map.of(
            Kind.REAL, "FLOAT", Kind.DOUBLE, "DOUBLE", Kind.DATE, "DATE",
            Kind.TIMESTAMP, "DATETIME(6)", Kind.TIMESTAMPTZ, "DATETIME(6)");

    @Override
    public String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public boolean safeAlias(String alias) {
        return ALIAS.matcher(alias).matches();
    }

    /** Read from the declaration only: the reported data type loses booleans, enums and signedness. */
    @Override
    public ColumnType columnType(SchemaInfo schema, String tableKey, String column, String sqlType) {
        ColumnDeclaration declaration = schema.getColumnDeclaration(tableKey, column);
        if (declaration == null || declaration.columnType() == null) {
            return ColumnType.unsupported(PermissionDialect.MYSQL);
        }
        MysqlColumnType declared = MysqlColumnType.parse(declaration.columnType());
        if (declared.isBoolean()) {
            return ColumnType.cast(PermissionDialect.MYSQL, Kind.BOOLEAN, null);
        }
        Kind kind = declared.unsigned() ? UNSIGNED.getOrDefault(declared.base(), SIGNED.get(declared.base()))
                : SIGNED.get(declared.base());
        if (kind == null) {
            return ColumnType.unsupported(PermissionDialect.MYSQL);
        }
        if (kind == Kind.TEXT || kind == Kind.ENUM) {
            return ColumnType.collated(PermissionDialect.MYSQL, kind, declared.labels(), declaration.charset(),
                    declaration.collation());
        }
        return ColumnType.cast(PermissionDialect.MYSQL, kind, null);
    }

    @Override
    public Object canonical(ColumnType type, Object value) {
        if (type.kind() == Kind.BOOLEAN && value instanceof Number number) {
            return switch (ColumnValues.convert(Kind.BIGINT, number).toString()) {
                case "1" -> Boolean.TRUE;
                case "0" -> Boolean.FALSE;
                default -> throw new IllegalArgumentException("Not a boolean");
            };
        }
        if (type.kind() == Kind.ENUM) {
            return label(type.labels(), value);
        }
        return ColumnValues.convert(type.kind(), value);
    }

    /** A label exactly as declared, or the 1-based position MySQL also reports an enum value by. */
    private static String label(List<String> labels, Object value) {
        if (value instanceof Number number) {
            long position = (Long) ColumnValues.convert(Kind.BIGINT, number);
            if (position < 1 || position > labels.size()) {
                throw new IllegalArgumentException("Not a position of the enum");
            }
            return labels.get((int) position - 1);
        }
        String text = (String) ColumnValues.convert(Kind.TEXT, value);
        if (!labels.contains(text)) {
            throw new IllegalArgumentException("Not a label of the enum");
        }
        return text;
    }

    @Override
    public Object compareValue(ColumnType type, Object canonical) {
        return switch (canonical) {
            case Instant instant -> LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
            case String label when type.kind() == Kind.ENUM -> type.labels().indexOf(label) + 1;
            default -> canonical;
        };
    }

    /** A TIMESTAMP is written with its offset, which MySQL converts from whatever the session zone is. */
    @Override
    public Object writeValue(ColumnType type, Object canonical) {
        return canonical instanceof Instant instant
                ? WRITTEN_TIMESTAMP.format(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)) : canonical;
    }

    @Override
    public String placeholder(ColumnType type, String param) {
        String cast = CASTS.get(type.kind());
        return cast == null ? ":" + param : "CAST(:" + param + " AS " + cast + ")";
    }

    @Override
    public Optional<String> imagePlaceholder(ColumnType type, String param) {
        return switch (type.kind()) {
            case SMALLINT, INTEGER, BIGINT, BOOLEAN, ENUM -> Optional.of("CAST(:" + param + " AS SIGNED)");
            case NUMERIC -> Optional.of(":" + param);
            case TEXT -> collatedText(type, param);
            default -> Optional.of(placeholder(type, param));
        };
    }

    /** Text compared under the column's own collation, not the connection's. */
    private static Optional<String> collatedText(ColumnType type, String param) {
        if (type.charset() == null || type.collation() == null || !CATALOG_NAME.matcher(type.charset()).matches()
                || !CATALOG_NAME.matcher(type.collation()).matches()) {
            return Optional.empty();
        }
        return Optional.of("CONVERT(:" + param + " USING " + type.charset() + ") COLLATE " + type.collation());
    }

    /** A TIMESTAMP is read as UTC; an image already holds it so. An enum compares by declaration position. */
    @Override
    public String compared(ColumnType type, String reference, boolean image) {
        if (type.kind() == Kind.TIMESTAMPTZ && !image) {
            return "CAST(" + reference + " AT TIME ZONE 'UTC' AS DATETIME(6))";
        }
        return type.kind() == Kind.ENUM ? "(" + reference + " + 0)" : reference;
    }

    @Override
    public String imageRow(String alias, List<String> placeholders, List<String> names) {
        return " FROM (VALUES ROW(" + String.join(", ", placeholders) + ")) AS " + alias
                + " (" + String.join(", ", names) + ")";
    }

    /** Whether two texts are equal is the collation's call (case, accents, trailing spaces). */
    @Override
    public boolean equatableInMemory(ColumnType type) {
        return type.kind() != Kind.TEXT;
    }

    @Override
    public boolean storesNonFiniteFloats() {
        return false;
    }

    @Override
    public boolean readsWrittenTableThroughDerivedTable() {
        return true;
    }

    @Override
    public boolean tracksFunctions() {
        return false;
    }
}
