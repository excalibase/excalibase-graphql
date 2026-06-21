package io.github.excalibase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        boolean jwtEnabled,
        Auth auth,
        MultiTenant multiTenant
) {

    public record Auth(
            String jwksUrl,
            String hmacSecret,
            String jwksTtlMinutes
    ) {
        public boolean hasJwksUrl() {
            return jwksUrl != null && !jwksUrl.isBlank();
        }

        public boolean hasHmacSecret() {
            return hmacSecret != null && !hmacSecret.isBlank();
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

    public boolean isMultiTenantEnabled() {
        return multiTenant != null && multiTenant.isConfigured();
    }
}
