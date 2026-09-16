package io.github.excalibase.security;

/**
 * SPI that answers whether a table is exposed at all to the current caller for
 * a given operation — the layer checked before row and column policies.
 * Implemented in {@code excalibase-graphql-api} (which owns the engine
 * dependency) and consulted by the compiler through {@link RlsContext}.
 *
 * <p>Deny by default: an implementation returns {@code false} for anything it
 * has no matching grant for. A {@code null} contributor in {@link RlsContext}
 * means the layer is not active for this request (the same condition under
 * which row policies are not active), not that everything is denied.
 */
@FunctionalInterface
public interface TableGrantContributor {

    /**
     * @param tableName the (possibly schema-qualified) table being reached
     * @param op        the operation being attempted
     * @return {@code true} iff a grant exposes this table/operation to the caller
     */
    boolean permits(String tableName, RlsOp op);
}
