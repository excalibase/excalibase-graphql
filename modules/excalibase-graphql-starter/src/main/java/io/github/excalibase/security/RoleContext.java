package io.github.excalibase.security;

/**
 * Per-request holder for the resolved Postgres role under which the current
 * request should run. Populated by {@code JwtAuthFilter} after the resolver
 * has validated the JWT scope/role claim, and read by data-plane code in any
 * module ({@code QueryExecutionService}, {@code RestApiController}, …) just
 * before issuing {@code SET LOCAL ROLE}.
 *
 * <p>Lives in the starter module so both {@code excalibase-graphql-api} (which
 * houses the resolver) and {@code excalibase-rest-api} (which can't depend on
 * the api module) can read the same value via a ThreadLocal — avoids forcing
 * cross-module Maven dependencies.
 *
 * <p>{@code null} means "no role switch for this request" — either the feature
 * is disabled, or the resolver chose to skip (e.g. unsupported request shape).
 */
public final class RoleContext {

    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private RoleContext() {}

    public static String getRole() {
        return ROLE.get();
    }

    public static void setRole(String role) {
        ROLE.set(role);
    }

    public static void clear() {
        ROLE.remove();
    }
}
