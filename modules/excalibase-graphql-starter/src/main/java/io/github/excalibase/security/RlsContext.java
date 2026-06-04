package io.github.excalibase.security;

/**
 * Per-request holder for the active {@link RlsWhereContributor}. Populated at
 * the start of a request (e.g. by {@code JwtAuthFilter}) once the project +
 * JWT claims are known, read by the compiler's {@code FilterBuilder} as it
 * builds each table's WHERE clause, and cleared in a {@code finally} so the
 * ThreadLocal never leaks across pooled request threads.
 *
 * <p>Lives in the starter module — alongside {@link RoleContext} — so the
 * generic compiler can consult it without depending on the api module or the
 * RLS engine.
 *
 * <p>{@code null} means "no RLS for this request": the feature is off, or the
 * request shape doesn't carry a project context.
 */
public final class RlsContext {

    private static final ThreadLocal<RlsWhereContributor> CONTRIBUTOR = new ThreadLocal<>();
    private static final ThreadLocal<ColumnMaskContributor> COLUMN_MASK = new ThreadLocal<>();

    private RlsContext() {}

    public static RlsWhereContributor current() {
        return CONTRIBUTOR.get();
    }

    public static void set(RlsWhereContributor contributor) {
        CONTRIBUTOR.set(contributor);
    }

    public static ColumnMaskContributor columnMask() {
        return COLUMN_MASK.get();
    }

    public static void setColumnMask(ColumnMaskContributor contributor) {
        COLUMN_MASK.set(contributor);
    }

    /** Clears both the row-filter and column-mask contributors for this thread. */
    public static void clear() {
        CONTRIBUTOR.remove();
        COLUMN_MASK.remove();
    }
}
