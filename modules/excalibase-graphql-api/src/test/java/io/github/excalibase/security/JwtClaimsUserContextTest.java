package io.github.excalibase.security;

import io.github.excalibase.rls.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Drives the {@link JwtClaimsUserContext} adapter that bridges a signed-in
 * {@link Principal} to the RLS engine's {@link UserContext}. Pure mapping: no Spring, no DB.
 */
class JwtClaimsUserContextTest {

    private static JwtClaims claims(String userId, String projectId, String role, String email) {
        return JwtClaims.of(userId, projectId, "acme", "demo", role, email);
    }

    private static UserContext contextOf(JwtClaims claims) {
        return new JwtClaimsUserContext(new Principal(claims.role(), false, claims, Map.of()));
    }

    @Test
    @DisplayName("userId/tenantId/roles/email are mapped from JwtClaims")
    void mapsCoreFieldsFromJwtClaims() {
        UserContext ctx = contextOf(claims("u-1", "p-1", "app_admin", "u@x.com"));

        assertThat(ctx.userId()).isEqualTo("u-1");
        assertThat(ctx.tenantId()).isEqualTo("p-1");
        assertThat(ctx.roles()).containsExactly("app_admin");
        assertThat(ctx.groupIds()).isEmpty();
    }

    @Test
    @DisplayName("roles set is a defensive copy and immutable")
    void rolesSetIsImmutable() {
        UserContext ctx = contextOf(claims("u-1", "p-1", "viewer", "v@x.com"));
        assertThat(ctx.roles()).isUnmodifiable();
    }

    @Test
    @DisplayName("roles hold the role the request runs as, not the token's default role")
    void rolesAreTheActiveRole() {
        JwtClaims claims = new JwtClaims("u-1", "p-1", "acme", "demo", "", "user", "u@x.com", null, 0L,
                Map.of(), Set.of("user", "editor"));
        UserContext ctx = new JwtClaimsUserContext(new Principal("editor", false, claims, Map.of()));

        assertThat(ctx.roles()).containsExactly("editor");
        assertThat(ctx.resolveVariable("role")).isEqualTo("editor");
    }

    @Test
    @DisplayName("a service token acting as a role is described by the session headers only")
    void impersonationUsesTheSessionHeaders() {
        JwtClaims service = new JwtClaims("svc-owner", "p-1", "acme", "demo", "", "service", "apikey:1",
                "service", 1L, Map.of("region", "us-east"));
        UserContext ctx = new JwtClaimsUserContext(new Principal("user", false, service,
                Map.of("x-excalibase-user-id", "77", "x-excalibase-region", "eu")));

        assertThat(ctx.userId()).isEqualTo("77");
        assertThat(ctx.tenantId()).isEqualTo("p-1");
        assertThat(ctx.roles()).containsExactly("user");
        assertThat(ctx.resolveVariable("user_id")).isEqualTo("77");
        assertThat(ctx.resolveVariable("region")).isEqualTo("eu");
        assertThat(ctx.resolveVariable("email")).isNull();
    }

    @Test
    @DisplayName("a service token acting as a role without a user id header has no user id")
    void impersonationWithoutUserIdHasNone() {
        JwtClaims service = new JwtClaims("svc-owner", "p-1", "acme", "demo", "", "service", "apikey:1",
                "service", 1L);
        UserContext ctx = new JwtClaimsUserContext(new Principal("user", false, service, Map.of()));

        assertThat(ctx.userId()).isNull();
    }

    @Test
    @DisplayName("a principal without claims is rejected — anonymous callers get the engine's anonymous context")
    void principalWithoutClaimsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new JwtClaimsUserContext(Principal.anonymous()));
    }

    @Test
    @DisplayName("resolveVariable returns user_id / project_id / role / email / scope")
    void resolveVariableSurfacesClaims() {
        UserContext ctx = contextOf(claims("u-1", "p-1", "app_admin", "u@x.com"));

        assertThat(ctx.resolveVariable("user_id")).isEqualTo("u-1");
        assertThat(ctx.resolveVariable("project_id")).isEqualTo("p-1");
        assertThat(ctx.resolveVariable("role")).isEqualTo("app_admin");
        assertThat(ctx.resolveVariable("email")).isEqualTo("u@x.com");
        // JwtClaims.of() defaults scope to "authenticated".
        assertThat(ctx.resolveVariable("scope")).isEqualTo("authenticated");
    }

    @Test
    @DisplayName("resolveVariable accepts camelCase aliases (userId, projectId)")
    void resolveVariableAcceptsCamelCaseAliases() {
        UserContext ctx = contextOf(claims("u-1", "p-1", "viewer", "u@x.com"));

        assertThat(ctx.resolveVariable("userId")).isEqualTo("u-1");
        assertThat(ctx.resolveVariable("projectId")).isEqualTo("p-1");
    }

    @Test
    @DisplayName("resolveVariable surfaces arbitrary custom claims from the JWT")
    void resolveVariableSurfacesCustomClaims() {
        JwtClaims c = new JwtClaims("u-1", "p-1", "acme", "demo", "", "viewer", "u@x.com", "authenticated", 0L,
                Map.of("region", "us-west", "plan", "pro"));
        UserContext ctx = contextOf(c);

        assertThat(ctx.resolveVariable("region")).isEqualTo("us-west");
        assertThat(ctx.resolveVariable("plan")).isEqualTo("pro");
        // known fields still take precedence over the raw claim map
        assertThat(ctx.resolveVariable("user_id")).isEqualTo("u-1");
    }

    @Test
    @DisplayName("resolveVariable returns null for unknown variables (engine default)")
    void resolveVariableReturnsNullForUnknown() {
        UserContext ctx = contextOf(claims("u-1", "p-1", "viewer", "u@x.com"));

        assertThat(ctx.resolveVariable("totally-unknown")).isNull();
        assertThat(ctx.resolveVariable("")).isNull();
        assertThat(ctx.resolveVariable(null)).isNull();
    }
}
