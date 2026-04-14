package io.github.excalibase.security;

/**
 * Verified JWT claims.
 *
 * <p>The {@code scope} and {@code keyId} fields are populated only for tokens
 * minted via the api-key grant in excalibase-auth (grant_type=api_key). For
 * password / refresh tokens these stay {@code null} / {@code 0} respectively
 * so legacy code paths see no observable change.
 *
 * <p>Use the {@link #of} factory when constructing claims that don't carry
 * api-key metadata — it's a one-arg shorter call site than the canonical
 * 8-arg constructor.
 */
public record JwtClaims(
        String userId,
        String projectId,
        String orgSlug,
        String projectName,
        String role,
        String email,
        String scope,
        long keyId
) {
    /**
     * Convenience factory for legacy 6-field call sites that pre-date the
     * scope/keyId additions. Defaults scope to "authenticated" and keyId to 0.
     */
    public static JwtClaims of(String userId, String projectId, String orgSlug,
                                String projectName, String role, String email) {
        return new JwtClaims(userId, projectId, orgSlug, projectName, role, email, "authenticated", 0L);
    }
}
