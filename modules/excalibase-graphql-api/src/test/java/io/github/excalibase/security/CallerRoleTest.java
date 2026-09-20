package io.github.excalibase.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exposure knows exactly two callers: one that is signed in and one that is not.
 * The free-form {@code role} claim is a row-level-policy input and says nothing
 * about which of the two a caller is — a token carrying {@code role=user} is a
 * signed-in caller, not a caller of some third kind.
 */
class CallerRoleTest {

    private static JwtClaims claimsWithRole(String role) {
        return JwtClaims.of("u-1", "proj-1", "acme", "demo", role, "u@x.com");
    }

    @Test
    void of_whenThereAreNoClaims_isAnon() {
        assertThat(CallerRole.of(null)).isEqualTo("anon");
    }

    @Test
    void of_whenTheFreeFormRoleIsTheJwtServiceDefault_isAuthenticated() {
        assertThat(CallerRole.of(claimsWithRole("user"))).isEqualTo("authenticated");
    }

    @Test
    void of_whenTheFreeFormRoleIsSomethingElse_isStillAuthenticated() {
        assertThat(CallerRole.of(claimsWithRole("app_admin"))).isEqualTo("authenticated");
        assertThat(CallerRole.of(claimsWithRole(null))).isEqualTo("authenticated");
    }

    @Test
    void of_whenTheFreeFormRoleSaysAnon_isStillAuthenticated() {
        assertThat(CallerRole.of(claimsWithRole("anon"))).isEqualTo("authenticated");
    }
}
