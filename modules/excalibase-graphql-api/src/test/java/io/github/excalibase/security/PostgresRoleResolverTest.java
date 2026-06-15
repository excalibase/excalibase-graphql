package io.github.excalibase.security;

import io.github.excalibase.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PostgresRoleResolver}. Drives the resolver via constructed
 * {@link SecurityProperties} and {@link JwtClaims} — no Spring context, no DB.
 */
class PostgresRoleResolverTest {

    private static SecurityProperties enabled(List<String> allowedRoles) {
        return new SecurityProperties(true, false, null, null,
                new SecurityProperties.Postgres(
                        new SecurityProperties.Postgres.RoleSwitching(
                                "app_anon",
                                "app_authenticated",
                                "app_service",
                                allowedRoles)));
    }

    private static SecurityProperties enabled() {
        return enabled(List.of("app_admin", "app_analytics"));
    }

    private static SecurityProperties disabled() {
        return new SecurityProperties(true, false, null, null, null);
    }

    private static JwtClaims claims(String scope, String role) {
        return new JwtClaims("u1", "p1", "org", "proj", "Org", role, "u@x.com", scope, 0L);
    }

    @Test
    void resolve_disabledFeature_returnsNull() {
        var resolver = new PostgresRoleResolver(disabled());
        assertThat(resolver.isEnabled()).isFalse();
        assertThat(resolver.resolve(claims("authenticated", "user"))).isNull();
        assertThat(resolver.resolve(claims("public", "user"))).isNull();
        assertThat(resolver.resolve(null)).isNull();
    }

    @Test
    void resolve_disabledFeature_blankAnonRole_returnsNull() {
        var properties = new SecurityProperties(true, false, null, null,
                new SecurityProperties.Postgres(
                        new SecurityProperties.Postgres.RoleSwitching(
                                "  ",  // blank → disabled
                                "app_authenticated",
                                "app_service",
                                List.of())));
        var resolver = new PostgresRoleResolver(properties);
        assertThat(resolver.isEnabled()).isFalse();
        assertThat(resolver.resolve(claims("public", "user"))).isNull();
    }

    @Test
    void resolve_publicScope_returnsAnonRole() {
        var resolver = new PostgresRoleResolver(enabled());
        assertThat(resolver.resolve(claims("public", "user"))).isEqualTo("app_anon");
    }

    @Test
    void resolve_publicScope_ignoresRoleClaim() {
        // role="hacker" must NOT escape anon when scope says public
        var resolver = new PostgresRoleResolver(enabled());
        assertThat(resolver.resolve(claims("public", "hacker"))).isEqualTo("app_anon");
    }

    @Test
    void resolve_serviceScope_returnsServiceRole() {
        var resolver = new PostgresRoleResolver(enabled());
        assertThat(resolver.resolve(claims("service", "user"))).isEqualTo("app_service");
    }

    @Test
    void resolve_authenticatedScope_defaultUserRole_returnsAuthenticatedDefault() {
        var resolver = new PostgresRoleResolver(enabled());
        assertThat(resolver.resolve(claims("authenticated", "user"))).isEqualTo("app_authenticated");
    }

    @Test
    void resolve_authenticatedScope_blankRoleClaim_returnsAuthenticatedDefault() {
        var resolver = new PostgresRoleResolver(enabled());
        assertThat(resolver.resolve(claims("authenticated", ""))).isEqualTo("app_authenticated");
    }

    @Test
    void resolve_nullScope_legacyToken_treatedAsAuthenticated() {
        var resolver = new PostgresRoleResolver(enabled());
        assertThat(resolver.resolve(claims(null, "user"))).isEqualTo("app_authenticated");
    }

    @Test
    void resolve_authenticatedScope_allowlistedRole_returnsThatRole() {
        var resolver = new PostgresRoleResolver(enabled());
        assertThat(resolver.resolve(claims("authenticated", "app_admin"))).isEqualTo("app_admin");
        assertThat(resolver.resolve(claims("authenticated", "app_analytics"))).isEqualTo("app_analytics");
    }

    @Test
    void resolve_authenticatedScope_roleNotInAllowlist_throws() {
        var resolver = new PostgresRoleResolver(enabled());
        var claims = claims("authenticated", "hacker");
        assertThatThrownBy(() -> resolver.resolve(claims))
                .isInstanceOf(RoleNotAllowedException.class)
                .hasMessageContaining("hacker");
    }

    @Test
    void resolve_authenticatedScope_emptyAllowlist_customRoleThrows() {
        var resolver = new PostgresRoleResolver(enabled(List.of()));
        // user role still works (mapped to default)
        assertThat(resolver.resolve(claims("authenticated", "user"))).isEqualTo("app_authenticated");
        // but non-default custom role is rejected
        var customRoleClaims = claims("authenticated", "app_admin");
        assertThatThrownBy(() -> resolver.resolve(customRoleClaims))
                .isInstanceOf(RoleNotAllowedException.class);
    }

    @Test
    void resolve_authenticatedScope_nullAllowlist_customRoleThrows() {
        var properties = new SecurityProperties(true, false, null, null,
                new SecurityProperties.Postgres(
                        new SecurityProperties.Postgres.RoleSwitching(
                                "app_anon", "app_authenticated", "app_service", null)));
        var resolver = new PostgresRoleResolver(properties);
        var customRoleClaims = claims("authenticated", "app_admin");
        assertThatThrownBy(() -> resolver.resolve(customRoleClaims))
                .isInstanceOf(RoleNotAllowedException.class);
    }

    @Test
    void resolve_unknownScope_throws() {
        var resolver = new PostgresRoleResolver(enabled());
        var claims = claims("hacker_scope", "user");
        assertThatThrownBy(() -> resolver.resolve(claims))
                .isInstanceOf(RoleNotAllowedException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void resolve_apiKeyScope_throws() {
        // Legacy "api-key" scope (pre-publishable/secret split) — explicit reject, fail-closed.
        var resolver = new PostgresRoleResolver(enabled());
        var claims = claims("api-key", "user");
        assertThatThrownBy(() -> resolver.resolve(claims))
                .isInstanceOf(RoleNotAllowedException.class);
    }

    @Test
    void resolve_nullClaims_singleTenantFallback_returnsAnon() {
        var resolver = new PostgresRoleResolver(enabled());
        assertThat(resolver.resolve(null)).isEqualTo("app_anon");
    }

    @Test
    void resolve_malformedAnonRole_throws() {
        var properties = new SecurityProperties(true, false, null, null,
                new SecurityProperties.Postgres(
                        new SecurityProperties.Postgres.RoleSwitching(
                                "drop-table; --",  // malformed identifier — still triggers feature (non-blank)
                                "app_authenticated",
                                "app_service",
                                List.of())));
        var resolver = new PostgresRoleResolver(properties);
        var claims = claims("public", "user");
        assertThatThrownBy(() -> resolver.resolve(claims))
                .isInstanceOf(RoleNotAllowedException.class);
    }

    @Test
    void resolve_malformedAuthenticatedDefaultRole_throws() {
        var properties = new SecurityProperties(true, false, null, null,
                new SecurityProperties.Postgres(
                        new SecurityProperties.Postgres.RoleSwitching(
                                "app_anon",
                                "1bad",  // starts with digit
                                "app_service",
                                List.of())));
        var resolver = new PostgresRoleResolver(properties);
        var claims = claims("authenticated", "user");
        assertThatThrownBy(() -> resolver.resolve(claims))
                .isInstanceOf(RoleNotAllowedException.class);
    }

    @Test
    void resolve_blankServiceRole_thrownAsConfigError() {
        var properties = new SecurityProperties(true, false, null, null,
                new SecurityProperties.Postgres(
                        new SecurityProperties.Postgres.RoleSwitching(
                                "app_anon",
                                "app_authenticated",
                                "  ",  // blank → config error when service scope arrives
                                List.of())));
        var resolver = new PostgresRoleResolver(properties);
        var claims = claims("service", "user");
        assertThatThrownBy(() -> resolver.resolve(claims))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service-role");
    }

    @Test
    void resolve_blankAuthenticatedDefaultRole_thrownAsConfigError() {
        var properties = new SecurityProperties(true, false, null, null,
                new SecurityProperties.Postgres(
                        new SecurityProperties.Postgres.RoleSwitching(
                                "app_anon",
                                "  ",
                                "app_service",
                                List.of())));
        var resolver = new PostgresRoleResolver(properties);
        var claims = claims("authenticated", "user");
        assertThatThrownBy(() -> resolver.resolve(claims))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authenticated-default-role");
    }

    @Test
    void resolve_allowlistedRoleStillValidatedForSafety() {
        // Even an allowlisted role must pass the SAFE_ROLE_NAME check — defense in depth
        // against an operator who copy-pastes a hostile string into config.
        var properties = new SecurityProperties(true, false, null, null,
                new SecurityProperties.Postgres(
                        new SecurityProperties.Postgres.RoleSwitching(
                                "app_anon",
                                "app_authenticated",
                                "app_service",
                                List.of("app_admin; --"))));
        var resolver = new PostgresRoleResolver(properties);
        var claims = claims("authenticated", "app_admin; --");
        assertThatThrownBy(() -> resolver.resolve(claims))
                .isInstanceOf(RoleNotAllowedException.class);
    }

    @Test
    void resolve_nullProperties_disabledByDefault() {
        var resolver = new PostgresRoleResolver((SecurityProperties) null);
        assertThat(resolver.isEnabled()).isFalse();
        assertThat(resolver.resolve(claims("authenticated", "user"))).isNull();
    }
}
