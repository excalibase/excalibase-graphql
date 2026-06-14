package io.github.excalibase.security;

import io.github.excalibase.rls.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Drives the {@link JwtClaimsUserContext} adapter that bridges the
 * GraphQL module's {@link JwtClaims} record to the RLS engine's
 * {@link UserContext} interface. Pure mapping: no Spring, no DB.
 */
class JwtClaimsUserContextTest {

    private static JwtClaims claims(String userId, String projectId, String role, String email) {
        return JwtClaims.of(userId, projectId, "acme", "demo", role, email);
    }

    @Test
    @DisplayName("userId/tenantId/roles/email are mapped from JwtClaims")
    void mapsCoreFieldsFromJwtClaims() {
        UserContext ctx = new JwtClaimsUserContext(claims("u-1", "p-1", "app_admin", "u@x.com"));

        assertThat(ctx.userId()).isEqualTo("u-1");
        assertThat(ctx.tenantId()).isEqualTo("p-1");
        assertThat(ctx.roles()).containsExactly("app_admin");
        assertThat(ctx.groupIds()).isEmpty();
    }

    @Test
    @DisplayName("roles set is a defensive copy and immutable")
    void rolesSetIsImmutable() {
        UserContext ctx = new JwtClaimsUserContext(claims("u-1", "p-1", "viewer", "v@x.com"));
        assertThat(ctx.roles()).isUnmodifiable();
    }

    @Test
    @DisplayName("null/blank role yields an empty role set (no \"\" element)")
    void blankRoleProducesEmptySet() {
        UserContext ctx = new JwtClaimsUserContext(claims("u-1", "p-1", "", "u@x.com"));
        assertThat(ctx.roles()).isEmpty();

        ctx = new JwtClaimsUserContext(claims("u-1", "p-1", null, "u@x.com"));
        assertThat(ctx.roles()).isEmpty();
    }

    @Test
    @DisplayName("null claims rejected — adapter requires a verified principal")
    void nullClaimsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new JwtClaimsUserContext(null));
    }

    @Test
    @DisplayName("resolveVariable returns user_id / project_id / role / email / scope")
    void resolveVariableSurfacesClaims() {
        UserContext ctx = new JwtClaimsUserContext(claims("u-1", "p-1", "app_admin", "u@x.com"));

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
        UserContext ctx = new JwtClaimsUserContext(claims("u-1", "p-1", "viewer", "u@x.com"));

        assertThat(ctx.resolveVariable("userId")).isEqualTo("u-1");
        assertThat(ctx.resolveVariable("projectId")).isEqualTo("p-1");
    }

    @Test
    @DisplayName("resolveVariable returns null for unknown variables (engine default)")
    void resolveVariableReturnsNullForUnknown() {
        UserContext ctx = new JwtClaimsUserContext(claims("u-1", "p-1", "viewer", "u@x.com"));

        assertThat(ctx.resolveVariable("totally-unknown")).isNull();
        assertThat(ctx.resolveVariable("")).isNull();
        assertThat(ctx.resolveVariable(null)).isNull();
    }
}
