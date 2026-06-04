package io.github.excalibase.rls.jdbc;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The output of {@link JdbcEvaluator#compile}: a parameterized SQL fragment
 * that the caller {@code AND}s into their own WHERE clause, plus the bind-param
 * map to merge into the caller's parameter map (typically a Spring
 * {@code NamedParameterJdbcTemplate} call).
 *
 * <p>Two sentinel values:
 * <ul>
 *   <li>{@link #UNRESTRICTED} — no policies in effect; caller's WHERE is
 *       used as-is.</li>
 *   <li>{@link #DENY_ALL} — RLS is on for the resource but no in-scope ALLOW
 *       matches; the {@code 1=0} fragment ensures zero rows.</li>
 * </ul>
 *
 * <p>Param keys are namespaced with the {@code rls_p} prefix so they do not
 * collide with the caller's own bind variables. Param values are already
 * coerced to their declared {@link io.github.excalibase.rls.FieldType} — UUIDs
 * arrive as {@link java.util.UUID}, dates as {@link java.time.LocalDate}, etc.
 */
public record SqlFilter(String sql, Map<String, Object> params) {

    public SqlFilter {
        sql = Objects.requireNonNullElse(sql, "");
        // Use HashMap-backed unmodifiable view because Map.copyOf rejects
        // null values, and bind params may legitimately be null (e.g. when
        // {{currentUserId}} resolves to null for an unauthenticated context;
        // the resulting "col = NULL" SQL evaluates to UNKNOWN and matches
        // zero rows, which is the correct safety behavior).
        params = (params == null || params.isEmpty())
            ? Map.of()
            : Collections.unmodifiableMap(new HashMap<>(params));
    }

    public static final SqlFilter UNRESTRICTED = new SqlFilter("", Map.of());
    public static final SqlFilter DENY_ALL = new SqlFilter("1=0", Map.of());

    public boolean isUnrestricted() { return sql.isEmpty(); }

    /**
     * Builds the full {@code WHERE} clause (including the {@code WHERE}
     * keyword) for a query that combines the caller's own filter expression
     * with this RLS predicate.
     *
     * <p>Handles all four (empty/non-empty) × (unrestricted/has-RLS)
     * combinations so callers never need to remember the {@code 1=1}
     * dance or build conditional concatenation.
     *
     * <p>Output shape, with a leading space so it concatenates directly
     * onto a base statement like {@code "SELECT * FROM orders"}:
     * <pre>
     *   userWhere empty, filter empty    → ""                                    (no WHERE at all)
     *   userWhere empty, filter present  → " WHERE &lt;rls&gt;"
     *   userWhere present, filter empty  → " WHERE (&lt;userWhere&gt;)"
     *   userWhere present, filter present → " WHERE (&lt;userWhere&gt;) AND (&lt;rls&gt;)"
     * </pre>
     *
     * @param userWhere the caller's WHERE expression (without the keyword),
     *                  e.g. {@code "status = :s AND amount > :min"}; treated
     *                  as empty when null or blank.
     */
    public String composeWhere(String userWhere) {
        boolean noUser = userWhere == null || userWhere.isBlank();
        boolean noRls = isUnrestricted();
        if (noUser && noRls) return "";
        if (noUser) return " WHERE " + sql;
        if (noRls) return " WHERE (" + userWhere + ")";
        return " WHERE (" + userWhere + ") AND (" + sql + ")";
    }
}
