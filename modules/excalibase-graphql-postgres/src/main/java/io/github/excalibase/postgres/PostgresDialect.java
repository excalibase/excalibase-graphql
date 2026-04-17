package io.github.excalibase.postgres;

import io.github.excalibase.SqlDialect;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * PostgreSQL implementation of {@link SqlDialect}.
 * Extracts all Postgres-specific SQL fragments from SqlCompiler.
 */
public class PostgresDialect implements SqlDialect {

    private static final String JSONB_CAST = "::jsonb";

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
     * Postgres FTS dispatch. All variants assume {@code colRef} is already a
     * {@code tsvector}. For plain {@code text} columns, use a
     * {@code GENERATED ALWAYS AS (to_tsvector(...))} column at the DB level.
     *
     * <ul>
     *   <li>{@link FtsVariant#PLAIN} → {@code col @@ plainto_tsquery(:param)}
     *       — stems + stop words + AND, always safe</li>
     *   <li>{@link FtsVariant#WEB_SEARCH} → {@code col @@ websearch_to_tsquery(:param)}
     *       — Google-style quotes, OR, minus exclusion, always safe</li>
     *   <li>{@link FtsVariant#PHRASE} → {@code col @@ phraseto_tsquery(:param)}
     *       — words must be adjacent in the document, always safe</li>
     *   <li>{@link FtsVariant#RAW} → {@code col @@ to_tsquery(:param)} — raw
     *       tsquery syntax ({@code foo & bar | baz}), throws on bad input.
     *       Only use when the input is known-valid.</li>
     * </ul>
     */
    @Override
    public Optional<String> fullTextSearchSql(String colRef, String paramRef, FtsVariant variant) {
        return switch (variant) {
            case PLAIN -> Optional.of(colRef + " @@ plainto_tsquery(" + paramRef + ")");
            case WEB_SEARCH -> Optional.of(colRef + " @@ websearch_to_tsquery(" + paramRef + ")");
            case PHRASE -> Optional.of(colRef + " @@ phraseto_tsquery(" + paramRef + ")");
            case RAW -> Optional.of(colRef + " @@ to_tsquery(" + paramRef + ")");
        };
    }

    /**
     * Postgres POSIX regex operators:
     * {@code col ~ :param} for case-sensitive,
     * {@code col ~* :param} for case-insensitive. Both accept standard POSIX
     * regex syntax. Unlike {@code LIKE}, regex predicates cannot use an
     * ordinary btree index — consider a GIN trigram index
     * ({@code pg_trgm}) for heavy use.
     */
    @Override
    public Optional<String> regexSql(String colRef, String paramRef, boolean caseInsensitive) {
        return Optional.of(colRef + (caseInsensitive ? " ~* " : " ~ ") + paramRef);
    }

    /**
     * Postgres JSONB predicates. Operators with a {@code ?} character
     * ({@code ?}, {@code ?&}, {@code ?|}) use their function-form equivalents
     * ({@code jsonb_exists}, {@code jsonb_exists_all}, {@code jsonb_exists_any})
     * to avoid clashing with Spring's {@code NamedParameterJdbcTemplate}
     * placeholder parser, which treats any {@code ?} in the SQL as a
     * positional bind and errors out on mixing with named parameters.
     * Containment and equality operators don't have this issue.
     */
    @Override
    public Optional<String> jsonPredicateSql(JsonPredicate variant, String colRef, String paramRef) {
        return switch (variant) {
            case EQ -> Optional.of(colRef + " = " + paramRef + JSONB_CAST);
            case NEQ -> Optional.of(colRef + " != " + paramRef + JSONB_CAST);
            case CONTAINS -> Optional.of(colRef + " @> " + paramRef + JSONB_CAST);
            case CONTAINED_BY -> Optional.of(colRef + " <@ " + paramRef + JSONB_CAST);
            case HAS_KEY -> Optional.of("jsonb_exists(" + colRef + ", " + paramRef + ")");
            case HAS_ALL_KEYS -> Optional.of("jsonb_exists_all(" + colRef + ", " + paramRef + ")");
            case HAS_ANY_KEYS -> Optional.of("jsonb_exists_any(" + colRef + ", " + paramRef + ")");
        };
    }

    /**
     * pgvector distance operators. Returns Optional.empty() for unknown
     * distance names so the caller can skip the vector clause on bad input
     * rather than emitting invalid SQL.
     */
    @Override
    public Optional<String> vectorDistanceOperator(String distance) {
        if (distance == null) return Optional.empty();
        return switch (distance.toUpperCase()) {
            case "L2", "EUCLIDEAN" -> Optional.of("<->");
            case "COSINE" -> Optional.of("<=>");
            case "IP", "INNER_PRODUCT" -> Optional.of("<#>");
            default -> Optional.empty();
        };
    }
}
