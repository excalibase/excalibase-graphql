package io.github.excalibase.security;

/**
 * The compiler's entry point into the grant (exposure) layer: refuses a table
 * the caller has no grant for before any SQL is built for it.
 *
 * <p>The layer is active exactly when a {@link TableGrantContributor} is
 * registered on {@link RlsContext} — the same request condition under which the
 * row-policy contributor is registered. With none registered (standalone /
 * single-database deployments with no control plane) nothing is checked, so
 * those deployments are unaffected.
 */
public final class GrantGuard {

    private GrantGuard() {
    }

    public static void require(String tableName, RlsOp op) {
        TableGrantContributor grants = RlsContext.grants();
        if (grants == null || tableName == null) {
            return;
        }
        if (!grants.permits(tableName, op)) {
            throw new GrantDeniedException(op.name(), tableName);
        }
    }
}
