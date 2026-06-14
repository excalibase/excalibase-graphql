package io.github.excalibase.security;

import java.util.Map;

/**
 * SPI that contributes a row-level-security predicate to a table's WHERE
 * clause during SQL compilation. Implemented in {@code excalibase-graphql-api}
 * (which owns the RLS engine dependency) and consulted by the compiler in
 * the starter module via {@link RlsContext} — dependency inversion keeps the
 * generic compiler free of any RLS-engine coupling, mirroring {@link RoleContext}.
 *
 * <p>The returned {@link Contribution} is already parameter-namespaced and
 * ready to splice: the compiler appends {@code (sql)} as one more WHERE
 * condition and merges {@code params} verbatim. A {@code null} return means
 * "no restriction for this table" — the common case when no policy targets it.
 */
public interface RlsWhereContributor {

    /**
     * @param tableName the (possibly schema-qualified) table being filtered
     * @param op        the operation the filter is for (SELECT for reads,
     *                  UPDATE/DELETE for those mutations) — drives which
     *                  policies apply and the default-deny outcome
     * @return a ready-to-splice predicate, or {@code null} if unrestricted
     */
    Contribution contribute(String tableName, RlsOp op);

    /**
     * A self-contained WHERE predicate plus its bind parameters. The {@code sql}
     * references only keys present in {@code params}, and those keys are unique
     * across all contributions within a single compiled query (the implementation
     * namespaces them) so merging into the shared param map never collides.
     */
    record Contribution(String sql, Map<String, Object> params) {}
}
