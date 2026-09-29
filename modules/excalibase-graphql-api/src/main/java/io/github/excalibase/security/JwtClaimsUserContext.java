package io.github.excalibase.security;

import io.github.excalibase.rls.UserContext;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adapter that exposes a signed-in {@link Principal} as the RLS engine's
 * {@link UserContext}. Pure mapping; no I/O.
 *
 * <p>{@link #roles()} is the one role the request runs as, never the token's raw
 * {@code role} claim. When a service token acts as another role, the caller is
 * described only by the request's session headers: the service token's own user id
 * and claims say nothing about whom it is acting for.
 *
 * <p>Snake_case variable names ({@code user_id}) are canonical for policy authors;
 * camelCase aliases match the JWT field names so a copied name never silently resolves
 * to null. Groups are always empty — the JWT pipeline carries no group membership.
 */
public final class JwtClaimsUserContext implements UserContext {

    private final Principal principal;
    private final JwtClaims claims;
    private final Map<String, String> sessionVariables;
    private final Set<String> roles;

    public JwtClaimsUserContext(Principal principal) {
        this.principal = Objects.requireNonNull(principal, "principal");
        this.claims = Objects.requireNonNull(principal.claims(), "principal.claims");
        this.sessionVariables = principal.sessionVariables(claims.projectId());
        this.roles = Set.of(principal.role());
    }

    @Override
    public String userId() {
        return principal.impersonating() ? sessionVariable("user-id") : claims.userId();
    }

    /** The project boundary is the tenant boundary, so the verified projectId doubles as tenant id. */
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
            case "user_id", "userId" -> userId();
            case "project_id", "projectId", "tenant_id", "tenantId" -> claims.projectId();
            case "role" -> principal.role();
            default -> principal.impersonating() ? sessionVariable(name) : claimVariable(name);
        };
    }

    private Object claimVariable(String name) {
        return switch (name) {
            case "email" -> claims.email();
            case "scope" -> claims.scope();
            case "org_slug", "orgSlug" -> claims.orgSlug();
            // Any other name falls through to the raw JWT claims ({{region}}, {{plan}}, …).
            default -> claims.extraClaims().get(name);
        };
    }

    private String sessionVariable(String name) {
        return sessionVariables.get(Principal.SESSION_VARIABLE_PREFIX
                + name.toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
