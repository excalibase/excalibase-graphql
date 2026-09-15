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
 *     provisioning-pat-file: /var/run/excalibase/graphql-token   # rotated in place
 *     provisioning-pat: ${PROVISIONING_PAT}                      # standalone/dev fallback
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
            String jwksTtlMinutes,
            String issuer,
            /**
             * EXC-11. When true (the default), a token is accepted only if its
             * {@code aud} covers the project in the request path. Settable to
             * false so a fleet still minting audience-less tokens can be rolled
             * forward in stages.
             */
            Boolean requireAud,
            /** Prefix in front of the projectId in {@code aud}. */
            String audPrefix
    ) {
        /** Defaults to enforcing the audience when the property is absent. */
        public boolean requireAudOrDefault() {
            return requireAud == null || requireAud;
        }
        public boolean hasJwksUrl() {
            return jwksUrl != null && !jwksUrl.isBlank();
        }

        public boolean hasHmacSecret() {
            return hmacSecret != null && !hmacSecret.isBlank();
        }
    }

    /**
     * {@code provisioningPatFile} wins over {@code provisioningPat} when set: the
     * platform mounts the token as a file and rotates it in place, so the literal
     * remains only as the standalone/dev fallback.
     */
    public record MultiTenant(
            String provisioningUrl,
            String provisioningPat,
            String provisioningPatFile
    ) {
        public boolean isConfigured() {
            return provisioningUrl != null && !provisioningUrl.isBlank();
        }
    }

    public boolean isMultiTenantEnabled() {
        return multiTenant != null && multiTenant.isConfigured();
    }
}
