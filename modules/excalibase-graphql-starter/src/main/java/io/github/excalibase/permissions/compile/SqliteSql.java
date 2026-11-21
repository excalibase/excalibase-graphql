package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.compile.ColumnType.Kind;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.SchemaInfo.ColumnDeclaration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * SQLite: double-quoted identifiers and a type table read from the column's declared type (SQLite itself
 * keeps only an affinity). Timestamps and dates live as fixed-width ISO text in UTC, so comparing the text
 * compares the instants; a LIKE is a GLOB, which is case sensitive as Postgres's
 * is whatever the connection's {@code case_sensitive_like} says; an image row is a SELECT of named values.
 */
public final class SqliteSql implements DialectSql {

    /** The text a timestamp with time zone is stored and compared as. */
    static final DateTimeFormatter STORED_TIMESTAMPTZ =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
    static final DateTimeFormatter STORED_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    private static final Pattern ALIAS = Pattern.compile("^(?:[A-Za-z_]\\w{0,62}|\"\\w{1,63}\")$");
    private static final Pattern DECLARED = Pattern.compile("[a-z][a-z0-9_ ]*");

    @Override
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public boolean safeAlias(String alias) {
        return ALIAS.matcher(alias).matches();
    }

    /** From the declared type only; a column declared as nothing, or as a type this table lacks, tests NULL alone. */
    @Override
    public ColumnType columnType(SchemaInfo schema, String tableKey, String column, String sqlType) {
        ColumnDeclaration declaration = schema.getColumnDeclaration(tableKey, column);
        String declared = declaration == null || declaration.columnType() == null ? sqlType : declaration.columnType();
        Kind kind = kindOf(declared);
        return kind == null ? ColumnType.unsupported(PermissionDialect.SQLITE)
                : ColumnType.cast(PermissionDialect.SQLITE, kind, null);
    }

    /** The kind a declared type name stands for; null when it is none this dialect compares. */
    static Kind kindOf(String declared) {
        String name = declared == null ? "" : declared.toLowerCase(Locale.ROOT).replaceAll("\\(.*\\)", "").trim();
        if (!DECLARED.matcher(name).matches()) {
            return null;
        }
        return switch (name) {
            case "smallint", "int2", "tinyint" -> Kind.SMALLINT;
            case "int", "int4", "mediumint" -> Kind.INTEGER;
            case "integer", "bigint", "int8" -> Kind.BIGINT;
            case "numeric", "decimal" -> Kind.NUMERIC;
            case "real", "double", "double precision", "float", "float4", "float8" -> Kind.DOUBLE;
            case "boolean", "bool" -> Kind.BOOLEAN;
            case "uuid" -> Kind.UUID;
            case "text", "varchar", "character varying", "char", "character", "clob", "string" -> Kind.TEXT;
            case "timestamptz", "timestamp with time zone" -> Kind.TIMESTAMPTZ;
            case "timestamp", "datetime", "timestamp without time zone" -> Kind.TIMESTAMP;
            case "date" -> Kind.DATE;
            default -> byAffinity(name);
        };
    }

    /** What SQLite's affinity rules make of a name it does not know: INT is an integer, CHAR, CLOB, TEXT text. */
    private static Kind byAffinity(String name) {
        if (name.contains("int")) {
            return Kind.BIGINT;
        }
        if (name.contains("char") || name.contains("clob") || name.contains("text")) {
            return Kind.TEXT;
        }
        return name.contains("real") || name.contains("floa") || name.contains("doub") ? Kind.DOUBLE : null;
    }

    @Override
    public Object canonical(ColumnType type, Object value) {
        return ColumnValues.convert(type.kind(), value);
    }

    /** What a bind of this value compares as: timestamps and dates as their stored text, a UUID as lower-case text. */
    @Override
    public Object compareValue(ColumnType type, Object canonical) {
        return switch (canonical) {
            case Instant instant -> STORED_TIMESTAMPTZ.format(instant);
            case LocalDateTime dateTime -> STORED_TIMESTAMP.format(dateTime);
            case LocalDate date -> date.toString();
            case java.util.UUID id -> id.toString();
            default -> canonical;
        };
    }

    @Override
    public Object writeValue(ColumnType type, Object canonical) {
        return compareValue(type, canonical);
    }

    /** A decimal is cast so it is a number whether or not the other side has an affinity to make it one. */
    @Override
    public String placeholder(ColumnType type, String param) {
        return type.kind() == Kind.NUMERIC ? "CAST(:" + param + " AS NUMERIC)" : ":" + param;
    }

    @Override
    public Optional<String> imagePlaceholder(ColumnType type, String param) {
        return Optional.of(placeholder(type, param));
    }

    @Override
    public String compared(ColumnType type, String reference, boolean image) {
        return switch (type.kind()) {
            case TIMESTAMPTZ -> storedTimestamp(reference, true);
            case TIMESTAMP -> storedTimestamp(reference, false);
            default -> reference;
        };
    }

    /**
     * A timestamp column in the one text form the engine writes, {@code yyyy-MM-ddTHH:mm:ss.SSSSSS[Z]}. SQLite's
     * own {@code CURRENT_TIMESTAMP} default writes {@code yyyy-MM-dd HH:mm:ss}, and text of the two forms
     * compares by its separator, not by the instant, so a row written by a default would be filtered wrongly.
     */
    public static String storedTimestamp(String reference, boolean withZone) {
        String zone = withZone ? " || 'Z'" : "";
        return "(CASE WHEN substr(" + reference + ", 11, 1) = ' ' THEN replace(substr(" + reference
                + " || CASE WHEN length(" + reference + ") = 19 THEN '.' ELSE '' END || '000000', 1, 26), ' ', 'T')"
                + zone + " ELSE " + reference + " END)";
    }

    /** SQLite names a derived table's columns inside it, not after it. */
    @Override
    public String imageRow(String alias, List<String> placeholders, List<String> names) {
        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            columns.append(i == 0 ? "" : ", ").append(placeholders.get(i)).append(" AS ").append(names.get(i));
        }
        return " FROM (SELECT " + columns + ") AS " + alias;
    }

    /** A column can declare its own collation, which only the database knows. */
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
        return false;
    }

    @Override
    public boolean tracksFunctions() {
        return false;
    }

    /** Postgres's pattern as a GLOB: a backslash makes the next character literal, {@code %} and {@code _} wildcards. */
    @Override
    public Object likePattern(String pattern) {
        StringBuilder glob = new StringBuilder();
        int[] codePoints = pattern.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i++) {
            if (codePoints[i] == '\\' && i + 1 < codePoints.length) {
                glob.append(literal(codePoints[++i]));
            } else if (codePoints[i] == '%') {
                glob.append('*');
            } else if (codePoints[i] == '_') {
                glob.append('?');
            } else {
                glob.append(literal(codePoints[i]));
            }
        }
        return glob.toString();
    }

    private static String literal(int codePoint) {
        String text = new String(Character.toChars(codePoint));
        return codePoint == '*' || codePoint == '?' || codePoint == '[' ? "[" + text + "]" : text;
    }

    /** A GLOB has no escape clause: a backslash was already turned into a literal in {@link #likePattern}. */
    @Override
    public String likeEscape() {
        return "";
    }

    @Override
    public String like(String column, String placeholder, boolean negated) {
        return column + (negated ? " NOT GLOB " : " GLOB ") + placeholder;
    }
}
