package io.github.excalibase.mysql;

import io.github.excalibase.SqlDialect;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * MySQL implementation of {@link SqlDialect}.
 * Uses MySQL-specific JSON functions and syntax.
 */
public class MysqlDialect implements SqlDialect {

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
        String t = dataType.toLowerCase();
        // MySQL JSON_OBJECT handles most types natively; bigint needs +0 to force numeric
        if (t.equals("bigint") || t.equals("int8")) return "+0";
        return "";
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

    @Override
    public String enumToText(String colRef) {
        return "upper(" + colRef + ")";
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
