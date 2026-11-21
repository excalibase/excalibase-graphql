package io.github.excalibase.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.DialectFeature;
import io.github.excalibase.ExactNumbers;
import io.github.excalibase.FilterCapabilities;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrorException;
import io.github.excalibase.permissions.compile.PermissionDialect;
import io.github.excalibase.permissions.compile.SqliteSql;
import org.springframework.jdbc.core.JdbcOperations;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Base64;
import java.util.HexFormat;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * SQLite implementation of {@link SqlDialect} (3.35 or newer, as sqlite-jdbc bundles). A request compiles to one
 * SELECT built with {@code json_object} and {@code json_group_array}. SQLite loses a JSON value's subtype when it
 * passes through a CTE or a derived table, so values are re-read with {@code json()} where they cross one.
 * Writes run as keyed writes in the caller's transaction (see {@link SqliteMutationCompiler}).
 */
public class SqliteDialect implements SqlDialect {

    private static final FilterCapabilities FILTER_CAPABILITIES = new SqliteFilterCapabilities();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Stored timestamps are fixed width, so comparing the text compares the instants. */
    private static final DateTimeFormatter STORED_TIMESTAMPTZ =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter STORED_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter INPUT = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral('T').optionalEnd().optionalStart().appendLiteral(' ').optionalEnd()
            .optionalStart().append(DateTimeFormatter.ISO_LOCAL_TIME).optionalEnd()
            .optionalStart().appendOffsetId().optionalEnd()
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .toFormatter(Locale.ROOT);

    @Override
    public PermissionDialect permissionDialect() {
        return PermissionDialect.SQLITE;
    }

    @Override
    public Optional<DataError> describeError(SQLException failure) {
        return SqliteErrors.describe(failure);
    }

    @Override
    public FilterCapabilities filterCapabilities() {
        return FILTER_CAPABILITIES;
    }

    @Override
    public String databaseName() {
        return "SQLite";
    }

    /** No SUBSCRIPTIONS: a file has no change feed. No DISTINCT_ON: SQLite has no such clause. */
    @Override
    public Set<DialectFeature> features() {
        return EnumSet.of(DialectFeature.AGGREGATES, DialectFeature.UPSERT);
    }

    /** SQLite's LIKE has no escape unless one is named; Postgres's is the backslash. */
    @Override
    public String userLikeEscape() {
        return " ESCAPE '\\'";
    }

    @Override
    public String comparableColumn(String colRef, String dataType) {
        if (dataType == null) {
            return colRef;
        }
        return switch (SqliteColumnTypes.engineType(dataType)) {
            case SqliteColumnTypes.TIMESTAMPTZ -> SqliteSql.storedTimestamp(colRef, true);
            case SqliteColumnTypes.TIMESTAMP -> SqliteSql.storedTimestamp(colRef, false);
            default -> colRef;
        };
    }

    /**
     * A DELETE that matches no row still takes SQLite's write lock, waiting out the busy timeout for it. Taking it
     * before the write's first read means the transaction never has to upgrade from a read, which fails at once.
     */
    @Override
    public void beforeWrite(JdbcOperations jdbc, String quotedTable) {
        jdbc.execute("DELETE FROM " + quotedTable + " WHERE 0");
    }

    @Override
    public boolean supportsRowToJson() {
        return false;
    }

    @Override
    public boolean parsesUntypedParameters() {
        return false;
    }

    @Override
    public boolean supportsFunctions() {
        return false;
    }

    @Override
    public String isDistinctFrom(String colRef, String paramRef) {
        return colRef + " IS NOT " + paramRef;
    }

    @Override
    public String fullTextDocument(String colRef) {
        return colRef;
    }

    @Override
    public String buildObject(List<String> keyValuePairs) {
        return "json_object(" + String.join(", ", keyValuePairs) + ")";
    }

    @Override
    public String aggregateArray(String expr) {
        return "json_group_array(json(" + expr + "))";
    }

    @Override
    public String coalesceArray(String expr) {
        return "json(COALESCE(" + expr + ", '[]'))";
    }

    @Override
    public String jsonArray(List<String> exprs) {
        return "json_array(" + String.join(", ", exprs) + ")";
    }

    @Override
    public String quoteIdentifier(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    /** A file's one schema is {@code main}; a table is qualified by it so a view or trigger cannot shadow it. */
    @Override
    public String qualifiedTable(String schema, String table) {
        return schema == null || schema.isBlank() ? quoteIdentifier(table)
                : quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }

    /** SQLite has no base64: the cursor is the hex of its JSON text, which is just as opaque. */
    @Override
    public String encodeCursor(String expr) {
        return "hex(CAST(" + expr + " AS BLOB))";
    }

    @Override
    public byte[] decodeCursor(String cursor) {
        return HexFormat.of().parseHex(cursor);
    }

    @Override
    public String ilike(String colRef, String paramRef) {
        return "LOWER(" + colRef + ") LIKE LOWER(" + paramRef + ")" + userLikeEscape();
    }

    @Override
    public String orderByNulls(String colRef, String direction, String nullsPosition) {
        return colRef + " " + direction + " NULLS " + ("LAST".equalsIgnoreCase(nullsPosition) ? "LAST" : "FIRST");
    }

    @Override
    public String suffixCast(String dataType) {
        return "";
    }

    /** What the API answers for a column: exact numbers as text, booleans and JSON as JSON, bytes as {@code \x} hex. */
    @Override
    public String selectColumn(String colRef, String dataType) {
        if (dataType == null) {
            return colRef;
        }
        if (ExactNumbers.isExact(dataType)) {
            return "CAST(" + colRef + " AS TEXT)";
        }
        return switch (dataType) {
            case SqliteColumnTypes.BOOLEAN -> jsonBool(colRef);
            case SqliteColumnTypes.JSON -> "CASE WHEN json_valid(" + colRef + ") THEN json(" + colRef + ") ELSE "
                    + colRef + " END";
            case SqliteColumnTypes.BYTES -> "CASE WHEN " + colRef + " IS NULL THEN NULL ELSE '\\x' || lower(hex("
                    + colRef + ")) END";
            default -> colRef;
        };
    }

    @Override
    public String jsonValue(String expr, String dataType) {
        return selectColumn(expr, dataType);
    }

    /**
     * Timestamps and dates are stored as fixed-width ISO text in UTC, bytes from their {@code \x} hex, JSON as
     * text, and a big integer as a decimal; anything the type cannot hold is refused as a client error.
     */
    @Override
    public Object bindValue(String columnType, Object value) {
        if (value == null || columnType == null) {
            return value;
        }
        return switch (columnType) {
            case SqliteColumnTypes.TIMESTAMPTZ -> timestamp(value, true);
            case SqliteColumnTypes.TIMESTAMP -> timestamp(value, false);
            case SqliteColumnTypes.DATE -> date(value);
            case SqliteColumnTypes.BYTES -> bytes(value);
            case SqliteColumnTypes.UUID -> value.toString().toLowerCase(Locale.ROOT);
            case SqliteColumnTypes.JSON -> json(value);
            default -> value instanceof BigInteger integer ? integer(integer) : value;
        };
    }

    private static Object integer(BigInteger integer) {
        return integer.bitLength() < Long.SIZE ? (Object) integer.longValue() : new BigDecimal(integer);
    }

    private static Object timestamp(Object value, boolean withZone) {
        if (!(value instanceof String text)) {
            return value;
        }
        try {
            TemporalAccessor parsed = INPUT.parse(text.trim());
            LocalDateTime local = parsed.isSupported(ChronoField.NANO_OF_SECOND)
                    ? LocalDateTime.from(parsed) : LocalDate.from(parsed).atStartOfDay();
            if (!withZone) {
                return STORED_TIMESTAMP.format(local);
            }
            OffsetDateTime at = parsed.isSupported(ChronoField.OFFSET_SECONDS)
                    ? OffsetDateTime.of(local, ZoneOffset.ofTotalSeconds(parsed.get(ChronoField.OFFSET_SECONDS)))
                    : local.atOffset(ZoneOffset.UTC);
            return STORED_TIMESTAMPTZ.format(at);
        } catch (DateTimeException e) {
            throw new DataErrorException(DataError.invalidValue(null, "Invalid timestamp: " + text));
        }
    }

    private static Object date(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        try {
            return LocalDate.parse(text.trim()).toString();
        } catch (DateTimeException e) {
            throw new DataErrorException(DataError.invalidValue(null, "Invalid date: " + text));
        }
    }

    private static Object bytes(Object value) {
        if (value instanceof String text && text.startsWith("\\x")) {
            try {
                return HexFormat.of().parseHex(text.substring(2));
            } catch (IllegalArgumentException e) {
                throw new DataErrorException(DataError.invalidValue(null,
                        "Invalid binary value: \\x must be followed by pairs of hex digits"));
            }
        }
        return value instanceof String text ? Base64.getDecoder().decode(text) : value;
    }

    private static Object json(Object value) {
        if (value instanceof String) {
            return value;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new DataErrorException(DataError.invalidValue(null, "Invalid JSON value"));
        }
    }

    /** {@code ON CONFLICT (keys) DO UPDATE SET c = excluded.c}, or DO NOTHING when there is nothing to update. */
    @Override
    public String onConflict(List<String> conflictCols, List<String> updateSetClauses) {
        String target = conflictCols.isEmpty() ? "" : " (" + conflictCols.stream().map(this::quoteIdentifier)
                .collect(Collectors.joining(", ")) + ")";
        if (updateSetClauses.isEmpty()) {
            return " ON CONFLICT" + target + " DO NOTHING";
        }
        String sets = updateSetClauses.stream()
                .map(col -> quoteIdentifier(col) + " = excluded." + quoteIdentifier(col))
                .collect(Collectors.joining(", "));
        return " ON CONFLICT" + target + " DO UPDATE SET " + sets;
    }

    /** SQLite does support RETURNING, but rows are read back by key so the write and its permission check agree. */
    @Override
    public boolean supportsReturning() {
        return false;
    }

    /** The transaction began with BEGIN IMMEDIATE, so it already holds the write lock: nothing to lock per row. */
    @Override
    public String lockRows() {
        return "";
    }

    /** SQLite compares a row value with a VALUES list, not with a list of tuples. */
    @Override
    public String rowValueList(List<String> tuples) {
        return "(VALUES " + String.join(", ", tuples) + ")";
    }

    @Override
    public String updateAliasKeyword() {
        return " AS ";
    }

    @Override
    public String cteName(String blockAlias, String suffix) {
        return "\"" + blockAlias.replace("\"", "") + suffix + "\"";
    }

    @Override
    public String randAlias() {
        return "\"" + UUID.randomUUID().toString().substring(0, 8) + "\"";
    }

    @Override
    public String distinctOn(List<String> columns, String alias) {
        throw new UnsupportedOperationException("SQLite does not support DISTINCT ON");
    }

    @Override
    public String jsonBool(String boolExpr) {
        return "json(CASE WHEN " + boolExpr + " THEN 'true' WHEN NOT " + boolExpr + " THEN 'false' END)";
    }

    @Override
    public String jsonBoolLiteral(boolean value) {
        return "json('" + value + "')";
    }

    @Override
    public String enumToText(String colRef) {
        return colRef;
    }

    @Override
    public String enumCast(String schema, String enumType) {
        return "";
    }

    // === Statement builders: writes run as separate statements (see SqliteMutationCompiler) ===

    @Override
    public String cteInsert(String alias, String table, String colsSql, String valsSql,
                            String onConflictSql, String objectSql) {
        return INSERT_INTO + table + parens(colsSql) + VALUES + parens(valsSql) + onConflictSql;
    }

    @Override
    public String cteBulkInsert(String alias, String table, String colsSql, String valueRowsSql, String objectSql) {
        return INSERT_INTO + table + parens(colsSql) + VALUES + valueRowsSql;
    }

    @Override
    public String cteUpdate(String alias, String table, String setClauses, String whereSql, String objectSql) {
        return UPDATE + table + " AS " + alias + SET + setClauses + whereSql;
    }

    @Override
    public String cteDelete(String alias, String table, String whereSql, String objectSql) {
        return DELETE_FROM + table + " AS " + alias + whereSql;
    }

    @Override
    public String wrapMutationResult(String mutationSql, String fieldName) {
        return mutationSql;
    }
}
