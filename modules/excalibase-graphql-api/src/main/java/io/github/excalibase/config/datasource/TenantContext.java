package io.github.excalibase.config.datasource;

/**
 * Per-request tenant context. Set by {@code JwtAuthFilter} on valid JWTs and read by
 * {@link DynamicRoutingDataSource} + anywhere else that needs per-tenant routing.
 *
 * <p>Both values flow through: {@code tenantId} (= {@code projectId}, opaque
 * {@code proj_XXXXXXXXXX} ref, globally unique) and {@code orgSlug} (needed for the
 * vault path {@code projects/{orgSlug}/{projectId}/credentials/...}).
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> ORG_SLUG = new ThreadLocal<>();

    private TenantContext() {}

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    public static String getOrgSlug() {
        return ORG_SLUG.get();
    }

    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static void setOrgSlug(String orgSlug) {
        ORG_SLUG.set(orgSlug);
    }

    public static void clear() {
        TENANT_ID.remove();
        ORG_SLUG.remove();
    }
}
