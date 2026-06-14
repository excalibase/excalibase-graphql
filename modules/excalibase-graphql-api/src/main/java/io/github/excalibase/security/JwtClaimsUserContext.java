package io.github.excalibase.security;

import io.github.excalibase.rls.UserContext;

import java.util.Objects;
import java.util.Set;

/**
 * Adapter that exposes a verified {@link JwtClaims} as the RLS engine's
 * {@link UserContext}. Pure mapping; no I/O.
 *
 * <p>Groups are intentionally always empty — the JWT pipeline does not
 * carry group membership yet. When/if the auth service starts issuing
 * a {@code groups} claim, populate it here.
 *
 * <p>{@link #resolveVariable} surfaces the small set of claim values that
 * RLS rules may reference as {@code ctx.*} variables. Snake_case keys
 * (e.g. {@code user_id}) are the canonical form documented for policy
 * authors; the camelCase aliases match the JSON field names of {@link
 * JwtClaims} so policy authors who copy/paste from a JWT payload don't
 * get a silent null.
 */
public final class JwtClaimsUserContext implements UserContext {

    private final JwtClaims claims;
    private final Set<String> roles;

    public JwtClaimsUserContext(JwtClaims claims) {
        this.claims = Objects.requireNonNull(claims, "claims");
        String role = claims.role();
        this.roles = (role == null || role.isBlank()) ? Set.of() : Set.of(role);
    }

    @Override
    public String userId() {
        return claims.userId();
    }

    /**
     * In excalibase the project boundary is the tenant boundary, so the
     * verified {@code projectId} JWT claim doubles as the RLS tenant id.
     */
    @Override
    public String tenantId() {
        return claims.projectId();
    }

    @Override
    public Set<String> roles() {
        return roles;
    }

    @Override
    public Set<String> groupIds() {
        return Set.of();
    }

    @Override
    public Object resolveVariable(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return switch (name) {
            case "user_id", "userId" -> claims.userId();
            case "project_id", "projectId", "tenant_id", "tenantId" -> claims.projectId();
            case "role" -> claims.role();
            case "email" -> claims.email();
            case "scope" -> claims.scope();
            case "org_slug", "orgSlug" -> claims.orgSlug();
            default -> null;
        };
    }
}
