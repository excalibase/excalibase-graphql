package io.github.excalibase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Security configuration for excalibase-graphql.
 *
 * Two supported verification modes (mutually exclusive):
 *
 * Mode 1 — JWKS endpoint (excalibase-auth ES256, Auth0, Keycloak, any OIDC provider):
 *   jwt-enabled: true
 *   auth:
 *     jwks-url: http://excalibase-auth:24000/.well-known/jwks.json
 *
 * Mode 2 — Shared HMAC secret (HS256, standalone / no auth service):
 *   jwt-enabled: true
 *   auth:
 *     hmac-secret: your-32-char-minimum-secret-here
 *
 * Mode 1 + multi-tenant (JWKS + per-tenant DB routing):
 *   jwt-enabled: true
 *   auth:
 *     jwks-url: http://excalibase-auth:24000/.well-known/jwks.json
 *   multi-tenant:
 *     provisioning-url: http://provisioning:24005/api
 *     provisioning-pat: ${PROVISIONING_PAT}
 *
 * Postgres role switching (opt-in, Postgres-only):
 *   postgres:
 *     role-switching:
 *       anon-role: app_anon
 *       authenticated-default-role: app_authenticated
 *       service-role: app_service
 *       allowed-roles: [app_admin, app_analytics]
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        boolean jwtEnabled,
        boolean verboseErrors,
        Auth auth,
        MultiTenant multiTenant,
        Postgres postgres
) {

    public record Auth(
            String jwksUrl,
            String hmacSecret,
            String jwksTtlMinutes,
            String expectedIssuer
    ) {
        public boolean hasJwksUrl() {
            return jwksUrl != null && !jwksUrl.isBlank();
        }

        public boolean hasHmacSecret() {
            return hmacSecret != null && !hmacSecret.isBlank();
        }

        /**
         * Expected JWT {@code iss} claim. Defaults to {@code "excalibase"} (matching the
         * auth service) when not configured. Set to blank to opt out of issuer validation.
         */
        public String expectedIssuerOrDefault() {
            return expectedIssuer == null ? "excalibase" : expectedIssuer;
        }
    }

    public record MultiTenant(
            String provisioningUrl,
            String provisioningPat
    ) {
        public boolean isConfigured() {
            return provisioningUrl != null && !provisioningUrl.isBlank();
        }
    }

    /**
     * Postgres-specific extensions. Currently only role switching — Postgres-only because
     * MySQL/MongoDB have no equivalent of {@code SET LOCAL ROLE}.
     */
    public record Postgres(
            RoleSwitching roleSwitching
    ) {
        /**
         * Maps the JWT {@code scope} claim (and optional {@code role} claim) to a Postgres
         * role that becomes the {@code current_user} for the request via {@code SET LOCAL ROLE}.
         *
         * <p>Feature is enabled iff {@link #anonRole()} is non-blank — presence-driven, no
         * separate boolean flag. When disabled the resolver returns {@code null} and the
         * data plane skips the role-switch step entirely.
         */
        public record RoleSwitching(
                String anonRole,
                String authenticatedDefaultRole,
                String serviceRole,
                List<String> allowedRoles
        ) {
            public boolean isEnabled() {
                return anonRole != null && !anonRole.isBlank();
            }
        }
    }

    public boolean isMultiTenantEnabled() {
        return multiTenant != null && multiTenant.isConfigured();
    }
}
