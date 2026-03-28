package io.github.excalibase;

import java.util.List;

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
}
