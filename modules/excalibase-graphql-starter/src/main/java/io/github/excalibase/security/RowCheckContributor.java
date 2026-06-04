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
}
