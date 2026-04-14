package io.github.excalibase;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction for database-specific SQL syntax.
 * Implementations: PostgresDialect, MysqlDialect (future).
 */
public interface SqlDialect {
    /** Build a JSON object from key-value pairs. PG: jsonb_build_object(k1, v1, ...) */
    String buildObject(List<String> keyValuePairs);

    /** Aggregate rows into a JSON array. PG: jsonb_agg(expr) */
    String aggregateArray(String expr);

    /** Coalesce null aggregate to empty array. PG: coalesce(expr, '[]'::jsonb) */
    String coalesceArray(String expr);

    default String rowToJson(String alias) {
        return "row_to_json(" + alias + ")";
    }

    /** Quote an identifier. PG: "id", MySQL: `id` */
    String quoteIdentifier(String id);

    /** Qualify a table with schema. PG: schema."table" */
    String qualifiedTable(String schema, String table);

    /** Encode a value as a cursor. PG: encode(convert_to(expr::text, 'utf-8'), 'base64') */
    String encodeCursor(String expr);

    /** Case-insensitive LIKE. PG: col ILIKE :param, MySQL: LOWER(col) LIKE LOWER(:param) */
    String ilike(String colRef, String paramRef);

    /** ORDER BY with nulls positioning. PG: ASC NULLS FIRST, MySQL: needs CASE workaround */
    String orderByNulls(String colRef, String direction, String nullsPosition);

    /** Suffix cast for type compatibility. PG: bigint->::text, json->#>>'{}'  */
    String suffixCast(String dataType);

    /** ON CONFLICT clause for upsert. PG: ON CONFLICT (cols) DO UPDATE SET ... */
    String onConflict(List<String> conflictCols, List<String> updateSetClauses);

    /** Whether this dialect supports RETURNING * */
    boolean supportsReturning();

    /** CTE name from block alias + suffix. Must handle quoting. */
    String cteName(String blockAlias, String suffix);

    /** Generate a random quoted alias. PG: "abc12345" */
    String randAlias();

    /** DISTINCT ON clause for deduplication. PG: DISTINCT ON ("col1", "col2") */
    String distinctOn(List<String> columns, String alias);

    /** Parameter cast suffix for non-standard types. PG: ::uuid, ::jsonb, etc. Empty string if no cast needed. */
    default String paramCast(String columnType) { return ""; }

    /**
     * Build the SQL fragment for a full-text search predicate, if this dialect
     * supports one. {@code colRef} is the qualified column reference (e.g.
     * {@code "t.body"}), {@code paramRef} is the bind parameter (e.g. {@code ":p_body_search"}),
     * and {@code useBm25} requests the higher-quality pg_search / BM25 path
     * when the underlying extension is installed.
     *
     * <p>Returns {@link Optional#empty()} when the dialect does not implement
     * FTS — callers should skip the operator on schemas it doesn't support.
     */
    default Optional<String> fullTextSearchSql(String colRef, String paramRef, boolean useBm25) {
        return Optional.empty();
    }

    /**
     * Maps a vector distance operator name to the dialect-specific SQL
     * operator used by {@code ORDER BY col &lt;OP&gt; :embedding}. Postgres
     * with pgvector exposes:
     * <ul>
     *   <li>{@code "L2"}     → {@code <->} (Euclidean / L2 distance)</li>
     *   <li>{@code "COSINE"} → {@code <=>} (cosine distance)</li>
     *   <li>{@code "IP"}     → {@code <#>} (negative inner product)</li>
     * </ul>
     * Returns {@link Optional#empty()} when the dialect doesn't ship a vector
     * type, allowing callers to silently skip the {@code _vector} operator on
     * unsupported backends. Unknown distance names also return empty.
     */
    default Optional<String> vectorDistanceOperator(String distance) {
        return Optional.empty();
    }

    /** Wrap a boolean expression for use inside JSON object builders.
     *  PG returns native boolean. MySQL needs CAST(IF(expr, 'true', 'false') AS JSON). */
    default String jsonBool(String boolExpr) {
        return boolExpr;
    }

    /** A literal boolean value for JSON context. PG: true/false. MySQL: CAST('true' AS JSON). */
    default String jsonBoolLiteral(boolean value) {
        return value ? "true" : "false";
    }

    /** Convert enum column to uppercase text for GraphQL convention.
     *  PG: upper(col::text), MySQL: upper(col) */
    default String enumToText(String colRef) {
        return "upper(" + colRef + "::text)";
    }

    /** Cast a parameter to an enum type. PG: ::schema."type", MySQL: "" (implicit) */
    default String enumCast(String schema, String enumType) {
        return "::" + schema + "." + quoteIdentifier(enumType);
    }

    // === CTE builder methods — dialect-specific SQL statement templates ===

    /** CTE INSERT returning single row: WITH alias AS (INSERT ... RETURNING *) SELECT obj FROM alias */
    default String cteInsert(String alias, String table, String colsSql, String valsSql,
                     String onConflictSql, String objectSql) {
        throw new UnsupportedOperationException("cteInsert not implemented for this dialect");
    }

    /** CTE bulk INSERT returning array: WITH alias AS (INSERT ... RETURNING *) SELECT agg(obj) FROM alias */
    default String cteBulkInsert(String alias, String table, String colsSql, String valueRowsSql, String objectSql) {
        throw new UnsupportedOperationException("cteBulkInsert not implemented for this dialect");
    }

    /** CTE UPDATE returning array: WITH alias AS (UPDATE ... RETURNING *) SELECT agg(obj) FROM alias */
    default String cteUpdate(String alias, String table, String setClauses, String whereSql, String objectSql) {
        throw new UnsupportedOperationException("cteUpdate not implemented for this dialect");
    }

    /** CTE DELETE returning array: WITH alias AS (DELETE ... RETURNING *) SELECT agg(obj) FROM alias */
    default String cteDelete(String alias, String table, String whereSql, String objectSql) {
        throw new UnsupportedOperationException("cteDelete not implemented for this dialect");
    }

    /** Wrap final mutation SQL with field name: SELECT jsonb_build_object('fieldName', (innerSql)) */
    default String wrapMutationResult(String mutationSql, String fieldName) {
        return mutationSql;
    }
}
