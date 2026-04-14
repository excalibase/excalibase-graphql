package io.github.excalibase.postgres;

import io.github.excalibase.SqlDialect;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * PostgreSQL implementation of {@link SqlDialect}.
 * Extracts all Postgres-specific SQL fragments from SqlCompiler.
 */
public class PostgresDialect implements SqlDialect {

    @Override
    public String buildObject(List<String> keyValuePairs) {
        return "jsonb_build_object(" + String.join(", ", keyValuePairs) + ")";
    }

    @Override
    public String aggregateArray(String expr) {
        return "jsonb_agg(" + expr + ")";
    }

    @Override
    public String coalesceArray(String expr) {
        return "coalesce(" + expr + ", '[]'::jsonb)";
    }

    @Override
    public String quoteIdentifier(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String qualifiedTable(String schema, String table) {
        return quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }

    @Override
    public String encodeCursor(String expr) {
        return "encode(convert_to(" + expr + "::text, 'utf-8'), 'base64')";
    }

    @Override
    public String ilike(String colRef, String paramRef) {
        return colRef + " ILIKE " + paramRef;
    }

    @Override
    public String orderByNulls(String colRef, String direction, String nullsPosition) {
        return colRef + " " + direction + " NULLS " + nullsPosition;
    }

    @Override
    public String suffixCast(String dataType) {
        if (dataType == null) return "";
        String t = dataType.toLowerCase();
        if (t.equals("bigint") || t.equals("int8")) return "::text";
        if (t.equals("json")) return " #>> '{}'";
        return "";
    }

    @Override
    public String onConflict(List<String> conflictCols, List<String> updateSetClauses) {
        String sets = updateSetClauses.stream()
                .map(col -> quoteIdentifier(col) + " = EXCLUDED." + quoteIdentifier(col))
                .collect(Collectors.joining(", "));
        // If single value looks like a constraint name (contains no spaces), use ON CONSTRAINT syntax
        if (conflictCols.size() == 1 && conflictCols.getFirst().matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return "ON CONFLICT ON CONSTRAINT " + quoteIdentifier(conflictCols.getFirst()) + " DO UPDATE SET " + sets;
        }
        String cols = conflictCols.stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
        return "ON CONFLICT (" + cols + ") DO UPDATE SET " + sets;
    }

    @Override
    public boolean supportsReturning() {
        return true;
    }

    @Override
    public String cteName(String blockAlias, String suffix) {
        String raw = blockAlias.replace("\"", "");
        return "\"" + raw + suffix + "\"";
    }

    @Override
    public String randAlias() {
        return "\"" + UUID.randomUUID().toString().substring(0, 8) + "\"";
    }

    @Override
    public String distinctOn(List<String> columns, String alias) {
        return "DISTINCT ON (" + columns.stream()
                .map(c -> alias + "." + quoteIdentifier(c))
                .collect(Collectors.joining(", ")) + ")";
    }

    // === CTE builder methods ===

    @Override
    public String cteInsert(String alias, String table, String colsSql, String valsSql,
                            String onConflictSql, String objectSql) {
        return WITH + alias + AS_OPEN + INSERT_INTO + table
                + parens(colsSql) + VALUES + parens(valsSql)
                + onConflictSql
                + RETURNING_ALL + " " + SELECT + objectSql + FROM + alias;
    }

    @Override
    public String cteBulkInsert(String alias, String table, String colsSql, String valueRowsSql, String objectSql) {
        return WITH + alias + AS_OPEN + INSERT_INTO + table
                + parens(colsSql) + VALUES + valueRowsSql
                + RETURNING_ALL + " " + SELECT + coalesceArray(aggregateArray(objectSql)) + FROM + alias;
    }

    @Override
    public String cteUpdate(String alias, String table, String setClauses, String whereSql, String objectSql) {
        return WITH + alias + AS_OPEN + UPDATE + table + " " + alias
                + SET + setClauses + whereSql
                + RETURNING_ALL + " " + SELECT + coalesceArray(aggregateArray(objectSql)) + FROM + alias;
    }

    @Override
    public String cteDelete(String alias, String table, String whereSql, String objectSql) {
        return WITH + alias + AS_OPEN + DELETE_FROM + table + " " + alias
                + whereSql
                + RETURNING_ALL + " " + SELECT + coalesceArray(aggregateArray(objectSql)) + FROM + alias;
    }

    @Override
    public String wrapMutationResult(String mutationSql, String fieldName) {
        int selectIdx = mutationSql.lastIndexOf(") " + SELECT.trim());
        if (selectIdx == -1) return mutationSql;
        String ctePart = mutationSql.substring(0, selectIdx + 1);
        String selectPart = mutationSql.substring(selectIdx + 2);
        return ctePart + " " + SELECT + "jsonb_build_object('" + fieldName + "', " + parens(selectPart) + ")";
    }

    @Override
    public String paramCast(String columnType) {
        if (columnType == null) return "";
        String t = columnType.toLowerCase();
        // Types that need explicit cast when binding via JDBC
        if (t.equals("uuid") || t.equals("inet") || t.equals("cidr") || t.equals("macaddr") || t.equals("macaddr8")
                || t.equals("jsonb") || t.equals("json") || t.equals("xml") || t.equals("bytea") || t.equals("interval")
                || t.startsWith("bit") || t.contains("timestamp") || t.equals("date") || t.equals("time")
                || t.contains("range") || t.equals("point") || t.equals("line") || t.equals("box") || t.equals("circle")
                || t.equals("path") || t.equals("polygon") || t.equals("tsvector")) {
            return "::" + t;
        }
        return "";
    }

    /**
     * Postgres FTS — emits {@code col @@ plainto_tsquery(:param)} against a
     * tsvector column. {@code plainto_tsquery} handles tokenization + stop
     * words + stemming under the server's default text search config, so
     * callers can pass a raw user query string without pre-processing.
     *
     * <p>The column reference must already be a {@code tsvector}. For plain
     * {@code text} columns, use a {@code GENERATED ALWAYS AS (to_tsvector(...))}
     * column or an index expression at the DB level.
     */
    @Override
    public java.util.Optional<String> fullTextSearchSql(String colRef, String paramRef) {
        return java.util.Optional.of(colRef + " @@ plainto_tsquery(" + paramRef + ")");
    }

    /**
     * pgvector distance operators. Returns Optional.empty() for unknown
     * distance names so the caller can skip the vector clause on bad input
     * rather than emitting invalid SQL.
     */
    @Override
    public java.util.Optional<String> vectorDistanceOperator(String distance) {
        if (distance == null) return java.util.Optional.empty();
        return switch (distance.toUpperCase()) {
            case "L2", "EUCLIDEAN" -> java.util.Optional.of("<->");
            case "COSINE" -> java.util.Optional.of("<=>");
            case "IP", "INNER_PRODUCT" -> java.util.Optional.of("<#>");
            default -> java.util.Optional.empty();
        };
    }
}
