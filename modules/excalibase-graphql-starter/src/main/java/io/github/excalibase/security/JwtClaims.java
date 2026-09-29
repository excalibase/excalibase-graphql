package io.github.excalibase.security;

import java.util.Map;
import java.util.Set;

/**
 * Verified JWT claims.
 *
 * <p>{@code role} is the token's default role and {@code allowedRoles} the roles it
 * may act as (always containing {@code role}). The {@code scope} and {@code keyId}
 * fields are populated only for tokens minted via the api-key grant in
 * excalibase-auth; for password / refresh tokens they stay {@code null} / {@code 0}.
 */
public record JwtClaims(
        String userId,
        String projectId,
        String orgSlug,
        String projectName,
        String orgName,
        String role,
        String email,
        String scope,
        long keyId,
        Map<String, Object> extraClaims,
        Set<String> allowedRoles
) {
    public JwtClaims {
        extraClaims = (extraClaims == null) ? Map.of() : Map.copyOf(extraClaims);
        allowedRoles = (allowedRoles == null) ? singleRole(role) : Set.copyOf(allowedRoles);
    }

    /** For call sites that carry no {@code allowed_roles}: the token may act only as {@code role}. */
    public JwtClaims(String userId, String projectId, String orgSlug, String projectName, String orgName,
                     String role, String email, String scope, long keyId, Map<String, Object> extraClaims) {
        this(userId, projectId, orgSlug, projectName, orgName, role, email, scope, keyId, extraClaims, null);
    }

    public JwtClaims(String userId, String projectId, String orgSlug, String projectName,
                     String orgName, String role, String email, String scope, long keyId) {
        this(userId, projectId, orgSlug, projectName, orgName, role, email, scope, keyId, Map.of(), null);
    }

    /** Shorter factory for claims that carry no api-key metadata (scope "authenticated", keyId 0). */
    public static JwtClaims of(String userId, String projectId, String orgSlug,
                               String projectName, String role, String email) {
        return new JwtClaims(userId, projectId, orgSlug, projectName, "", role, email, "authenticated", 0L);
    }

    private static Set<String> singleRole(String role) {
        return role == null ? Set.of() : Set.of(role);
    }
}
