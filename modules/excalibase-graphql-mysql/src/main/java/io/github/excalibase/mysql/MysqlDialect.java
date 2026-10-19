package io.github.excalibase.mysql;

import io.github.excalibase.FilterCapabilities;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.errors.DataError;
import io.github.excalibase.permissions.compile.PermissionDialect;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * MySQL implementation of {@link SqlDialect}.
 * Uses MySQL-specific JSON functions and syntax. Column types are the ones {@link MysqlColumnTypes} reflects.
 */
public class MysqlDialect implements SqlDialect {

    private static final FilterCapabilities FILTER_CAPABILITIES = new MysqlFilterCapabilities();
    private static final String UTC = "'+00:00'";

    @Override
    public PermissionDialect permissionDialect() {
        return PermissionDialect.MYSQL;
    }

    @Override
    public Optional<DataError> describeError(SQLException failure) {
        return MysqlErrors.describe(failure);
    }

    @Override
    public FilterCapabilities filterCapabilities() {
        return FILTER_CAPABILITIES;
    }

    /** REGEXP_LIKE with an explicit match type, so case follows the operator rather than the column's collation. */
    @Override
    public Optional<String> regexSql(String colRef, String paramRef, boolean caseInsensitive) {
        return Optional.of("REGEXP_LIKE(" + colRef + ", " + paramRef + ", '" + (caseInsensitive ? "i" : "c") + "')");
    }

    /** MATCH ... AGAINST in BOOLEAN MODE; the column needs its own FULLTEXT index. MySQL has no web-search syntax. */
    @Override
    public Optional<String> fullTextSearchSql(String colRef, String paramRef, FtsVariant variant) {
        if (variant == FtsVariant.WEB_SEARCH) return Optional.empty();
        return Optional.of("MATCH(" + colRef + ") AGAINST(" + paramRef + " IN BOOLEAN MODE)");
    }

    @Override
    public String fullTextSearchValue(FtsVariant variant, String text) {
        return MysqlFullText.query(variant, text);
    }

    @Override
    public Optional<String> jsonPredicateSql(JsonPredicate variant, String colRef, String paramRef) {
        return switch (variant) {
            case CONTAINS -> Optional.of("JSON_CONTAINS(" + colRef + ", CAST(" + paramRef + " AS JSON))");
            case CONTAINED_BY -> Optional.of("JSON_CONTAINS(CAST(" + paramRef + " AS JSON), " + colRef + ")");
            case HAS_KEY -> Optional.of("JSON_CONTAINS_PATH(" + colRef + ", 'one', " + keyPath(paramRef) + ")");
            case EQ, NEQ, HAS_ALL_KEYS, HAS_ANY_KEYS -> Optional.empty();
        };
    }

    @Override
    public Optional<String> jsonKeysPredicateSql(JsonPredicate variant, String colRef, List<String> keyParamRefs) {
        if (variant != JsonPredicate.HAS_ALL_KEYS && variant != JsonPredicate.HAS_ANY_KEYS) return Optional.empty();
        String mode = variant == JsonPredicate.HAS_ALL_KEYS ? "all" : "one";
        String paths = keyParamRefs.stream().map(MysqlDialect::keyPath).collect(Collectors.joining(", "));
        return Optional.of("JSON_CONTAINS_PATH(" + colRef + ", '" + mode + "', " + paths + ")");
    }

    /** The JSON path of a top-level key; quoting lets a key hold dots or spaces. */
    private static String keyPath(String paramRef) {
        return "CONCAT('$.', JSON_QUOTE(" + paramRef + "))";
    }

    @Override
    public boolean supportsRowToJson() {
        return false;
    }

    @Override
    public String databaseName() {
        return "MySQL";
    }

    @Override
    public String isDistinctFrom(String colRef, String paramRef) {
        return "NOT (" + colRef + " <=> " + paramRef + ")";
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
    public String fullTextDocument(String colRef) {
        return colRef;
    }

    @Override
    public String buildObject(List<String> keyValuePairs) {
        return "JSON_OBJECT(" + String.join(", ", keyValuePairs) + ")";
    }

    @Override
    public String aggregateArray(String expr) {
        return "JSON_ARRAYAGG(" + expr + ")";
    }

    @Override
    public String coalesceArray(String expr) {
        return "IFNULL(" + expr + ", JSON_ARRAY())";
    }

    @Override
    public String quoteIdentifier(String id) {
        return "`" + id.replace("`", "``") + "`";
    }

    @Override
    public String qualifiedTable(String schema, String table) {
        return quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }

    @Override
    public String encodeCursor(String expr) {
        return "TO_BASE64(CAST(" + expr + " AS CHAR))";
    }

    @Override
    public String ilike(String colRef, String paramRef) {
        return "LOWER(" + colRef + ") LIKE LOWER(" + paramRef + ")";
    }

    @Override
    public String orderByNulls(String colRef, String direction, String nullsPosition) {
        // MySQL workaround: CASE WHEN col IS NULL THEN 1 ELSE 0 END for NULLS LAST
        // and CASE WHEN col IS NULL THEN 0 ELSE 1 END for NULLS FIRST
        if ("LAST".equalsIgnoreCase(nullsPosition)) {
            return "CASE WHEN " + colRef + " IS NULL THEN 1 ELSE 0 END, " + colRef + " " + direction;
        } else {
            return "CASE WHEN " + colRef + " IS NULL THEN 0 ELSE 1 END, " + colRef + " " + direction;
        }
    }

    @Override
    public String suffixCast(String dataType) {
        if (dataType == null) return "";
        String type = dataType.toLowerCase();
        // MySQL JSON_OBJECT handles most types natively; bigint needs +0 to force numeric
        if (type.equals("bigint") || type.equals("int8")) return "+0";
        return "";
    }

    /** Booleans as JSON booleans and datetimes in ISO 8601, as the Postgres dialect renders them. */
    @Override
    public String selectColumn(String colRef, String dataType) {
        if (dataType == null) return colRef;
        return switch (dataType) {
            case MysqlColumnTypes.BOOLEAN -> "IF(" + colRef + " IS NULL, NULL, IF(" + colRef + ", "
                    + jsonBoolLiteral(true) + ", " + jsonBoolLiteral(false) + "))";
            case MysqlColumnTypes.WITHOUT_ZONE -> isoDateTime(colRef);
            case MysqlColumnTypes.WITH_ZONE -> "CONCAT(" + isoDateTime("CONVERT_TZ(" + colRef
                    + ", @@session.time_zone, " + UTC + ")") + ", " + UTC + ")";
            default -> colRef + suffixCast(dataType);
        };
    }

    /** JSON_OBJECT writes a boolean as 1 and a datetime with a space and no zone; the API's form is used instead. */
    @Override
    public String jsonValue(String expr, String dataType) {
        return selectColumn(expr, dataType);
    }

    /** MySQL reads an offset but not {@code Z}, which is what ISO 8601 clients send for UTC. */
    @Override
    public Object bindValue(String columnType, Object value) {
        boolean dateTime = MysqlColumnTypes.WITHOUT_ZONE.equals(columnType) || MysqlColumnTypes.WITH_ZONE.equals(columnType);
        if (dateTime && value instanceof String text && (text.endsWith("Z") || text.endsWith("z"))) {
            return text.substring(0, text.length() - 1) + "+00:00";
        }
        return value;
    }

    /** {@code yyyy-MM-ddTHH:mm:ss}, with the fraction only when there is one, trailing zeros dropped. */
    private static String isoDateTime(String expr) {
        return "CONCAT(DATE_FORMAT(" + expr + ", '%Y-%m-%dT%H:%i:%s'), IF(MICROSECOND(" + expr + ") = 0, '', "
                + "TRIM(TRAILING '0' FROM DATE_FORMAT(" + expr + ", '.%f'))))";
    }

    @Override
    public String onConflict(List<String> conflictCols, List<String> updateSetClauses) {
        String sets = updateSetClauses.stream()
                .map(col -> quoteIdentifier(col) + " = VALUES(" + quoteIdentifier(col) + ")")
                .collect(Collectors.joining(", "));
        return "ON DUPLICATE KEY UPDATE " + sets;
    }

    @Override
    public boolean supportsReturning() {
        return false;
    }

    @Override
    public String cteName(String blockAlias, String suffix) {
        String raw = blockAlias.replace("`", "");
        return "`" + raw + suffix + "`";
    }

    @Override
    public String randAlias() {
        return "`" + UUID.randomUUID().toString().substring(0, 8) + "`";
    }

    @Override
    public String distinctOn(List<String> columns, String alias) {
        // MySQL doesn't support DISTINCT ON. Use GROUP BY as a workaround — caller handles this.
        throw new UnsupportedOperationException("MySQL does not support DISTINCT ON");
    }

    @Override
    public String jsonBool(String boolExpr) {
        return "CAST(IF(" + boolExpr + ", 'true', 'false') AS JSON)";
    }

    @Override
    public String jsonBoolLiteral(boolean value) {
        return "CAST('" + value + "' AS JSON)";
    }

    /** An ENUM column answers its label, which is the GraphQL enum value's name. */
    @Override
    public String enumToText(String colRef) {
        return colRef;
    }

    @Override
    public String enumCast(String schema, String enumType) {
        return ""; // MySQL handles string-to-enum conversion implicitly
    }

    // === CTE builder methods (MySQL uses two-phase, not CTEs) ===

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
        return UPDATE + table + " " + alias + SET + setClauses + whereSql;
    }

    @Override
    public String cteDelete(String alias, String table, String whereSql, String objectSql) {
        return DELETE_FROM + table + " " + alias + whereSql;
    }

    @Override
    public String wrapMutationResult(String mutationSql, String fieldName) {
        // MySQL wraps at the select phase, not at CTE level
        return mutationSql;
    }
}
