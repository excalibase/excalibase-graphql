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
     * FTS variants supported by {@link #fullTextSearchSql(String, String, FtsVariant)}.
     * <ul>
     *   <li>{@link #PLAIN} — {@code plainto_tsquery}: raw user text, stems
     *       and drops stop words, always safe.</li>
     *   <li>{@link #WEB_SEARCH} — {@code websearch_to_tsquery}: Google-style
     *       syntax with {@code "phrase"}, {@code OR}, {@code -exclude}.
     *       Always safe against bad input.</li>
     *   <li>{@link #PHRASE} — {@code phraseto_tsquery}: words must be
     *       adjacent in the document in the order given. Safe input.</li>
     *   <li>{@link #RAW} — {@code to_tsquery}: raw tsquery syntax
     *       ({@code foo & bar | baz}). Throws on malformed input — only
     *       use when the caller knows the input is well-formed.</li>
     * </ul>
     */
    enum FtsVariant { PLAIN, WEB_SEARCH, PHRASE, RAW }

    /**
     * Build the SQL fragment for a full-text search predicate against a
     * tsvector column. {@code colRef} is the qualified column reference
     * (e.g. {@code "t.search_vec"}), {@code paramRef} is the bind parameter
     * (e.g. {@code ":p_body_search_0"}), and {@code variant} selects the
     * tsquery function to wrap the bind with.
     *
     * <p>Returns {@link Optional#empty()} when the dialect does not implement
     * FTS — callers should skip the operator on schemas it doesn't support.
     */
    default Optional<String> fullTextSearchSql(String colRef, String paramRef, FtsVariant variant) {
        return Optional.empty();
    }

    /**
     * Build the SQL fragment for a POSIX regex predicate on a text column.
     * Postgres uses {@code col ~ :param} (case-sensitive) and
     * {@code col ~* :param} (case-insensitive). MySQL uses
     * {@code col REGEXP :param} and {@code col REGEXP BINARY :param}.
     * Returns {@link Optional#empty()} when the dialect doesn't support
     * regex predicates so the caller can silently skip the operator.
     *
     * @param caseInsensitive {@code true} for the {@code iregex}/{@code imatch}
     *        variant, {@code false} for the case-sensitive {@code regex}/{@code match} variant
     */
    default Optional<String> regexSql(String colRef, String paramRef, boolean caseInsensitive) {
        return Optional.empty();
    }

    /**
     * JSON predicate variants supported by {@link #jsonPredicateSql(JsonPredicate, String, String)}.
     * Each maps to a Postgres jsonb operator or function:
     * <ul>
     *   <li>{@link #EQ} / {@link #NEQ} — direct equality on jsonb with {@code ::jsonb} cast</li>
     *   <li>{@link #CONTAINS} → {@code @>}</li>
     *   <li>{@link #CONTAINED_BY} → {@code <@}</li>
     *   <li>{@link #HAS_KEY} → {@code jsonb_exists(col, :param)} — function form
     *       avoids the {@code ?} character clash with JDBC placeholder parsing</li>
     *   <li>{@link #HAS_ALL_KEYS} → {@code jsonb_exists_all(col, :param)} — all
     *       requested keys must be present</li>
     *   <li>{@link #HAS_ANY_KEYS} → {@code jsonb_exists_any(col, :param)} — at
     *       least one requested key must be present</li>
     * </ul>
     */
    enum JsonPredicate { EQ, NEQ, CONTAINS, CONTAINED_BY, HAS_KEY, HAS_ALL_KEYS, HAS_ANY_KEYS }

    /**
     * Build the SQL fragment for a JSONB predicate. {@code colRef} is the
     * qualified column reference, {@code paramRef} is the bind parameter
     * (e.g. {@code ":p_metadata_contains_0"}), and {@code variant} selects
     * the Postgres operator / function to wrap the bind with.
     *
     * <p>Returns {@link Optional#empty()} when the dialect doesn't support
     * JSONB predicates so callers can silently skip the operator on
     * backends without json support.
     */
    default Optional<String> jsonPredicateSql(JsonPredicate variant, String colRef, String paramRef) {
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
     * type, allowing callers to silently skip the {@code vector} operator on
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
