package io.github.excalibase.security;

import java.util.Map;

/**
 * SPI that validates a candidate row against row-level policies — the
 * WITH-CHECK half of RLS, used for INSERT (and could back UPDATE's new-row
 * check). Implemented in {@code excalibase-graphql-api} over the engine's
 * {@code RowMatcher} and consulted by the mutation compiler via
 * {@link RlsContext}.
 *
 * <p>Returning {@code false} means the row is not permitted for the caller and
 * the mutation must be rejected before any SQL runs.
 */
public interface RowCheckContributor {

    /**
     * @param tableName the (possibly schema-qualified) target table
     * @param row       the candidate row's column → value map
     * @param op        the operation being checked (typically {@link RlsOp#INSERT})
     * @return {@code true} if the row satisfies the policies (or RLS is off for
     *         this resource/op); {@code false} if it must be rejected
     */
    boolean permits(String tableName, Map<String, Object> row, RlsOp op);

    /**
     * WITH-CHECK for an UPDATE's partial new image: {@code changedColumns} holds
     * only the columns the mutation is setting. Returns {@code false} when those
     * changes would move the row out of the caller's UPDATE policies (e.g.
     * reassigning an ownership column), in which case the mutation must be
     * rejected before any SQL runs. Returns {@code true} when no UPDATE policy
     * governs a changed column, preserving the engine's permissive default.
     *
     * <p>Default-implemented over {@link #permits} so existing contributors keep
     * compiling; the engine-backed implementation overrides it with new-image
     * semantics that do not falsely reject untouched policy columns.
     */
    default boolean permitsUpdate(String tableName, Map<String, Object> changedColumns) {
        return permits(tableName, changedColumns, RlsOp.UPDATE);
    }
}
