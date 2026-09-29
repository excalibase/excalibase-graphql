package io.github.excalibase.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which role a request runs as, per docs/features/permissions.md §1.1 — the full matrix. */
class RoleResolverTest {

    private static final Map<String, String> NO_HEADERS = Map.of();
    private static final Map<String, String> IMPERSONATION_HEADERS = Map.of(
            "X-Excalibase-Role", "user", "X-Excalibase-User-Id", "77", "x-excalibase-plan", "gold");

    private static JwtClaims token(String role, String scope, Set<String> allowedRoles) {
        return new JwtClaims("u-1", "proj-a", "org", "app", "Org", role, "u@test.com", scope, 0L,
                Map.of("role", role), allowedRoles);
    }

    private static final JwtClaims USER = token("user", null, Set.of("user"));
    private static final JwtClaims EDITOR_CAPABLE = token("user", null, Set.of("user", "editor"));
    private static final JwtClaims SERVICE = token("service", "service", Set.of("service"));

    private static void assertRoleNotAllowed(JwtClaims claims, String requestedRole) {
        assertThatThrownBy(() -> RoleResolver.resolve(claims, requestedRole, NO_HEADERS))
                .isInstanceOf(RoleNotAllowedException.class)
                .extracting(thrown -> ((RoleNotAllowedException) thrown).code())
                .isEqualTo("role_not_allowed");
    }

    // --- no token ---

    @Test
    void noToken_noHeader_runsAsAnon() {
        Principal principal = RoleResolver.resolve(null, null, NO_HEADERS);

        assertThat(principal.role()).isEqualTo("anon");
        assertThat(principal.bypass()).isFalse();
        assertThat(principal.claims()).isNull();
    }

    @Test
    void noToken_anonHeader_runsAsAnon() {
        assertThat(RoleResolver.resolve(null, "anon", NO_HEADERS).role()).isEqualTo("anon");
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "service", "editor", "", "Anon"})
    void noToken_anyOtherHeader_isNotAllowed(String requestedRole) {
        assertRoleNotAllowed(null, requestedRole);
    }

    // --- ordinary tokens ---

    @Test
    void userToken_noHeader_runsAsTheDefaultRole() {
        Principal principal = RoleResolver.resolve(USER, null, NO_HEADERS);

        assertThat(principal.role()).isEqualTo("user");
        assertThat(principal.bypass()).isFalse();
        assertThat(principal.claims()).isSameAs(USER);
    }

    @Test
    void userToken_headerNamingTheDefaultRole_runsAsIt() {
        assertThat(RoleResolver.resolve(USER, "user", NO_HEADERS).role()).isEqualTo("user");
    }

    @ParameterizedTest
    @ValueSource(strings = {"editor", "anon", "service", "", "User"})
    void userToken_headerNamingARoleNotAllowed_isNotAllowed(String requestedRole) {
        assertRoleNotAllowed(USER, requestedRole);
    }

    @Test
    void tokenWithAllowedRoles_headerPicksOneOfThem() {
        Principal principal = RoleResolver.resolve(EDITOR_CAPABLE, "editor", NO_HEADERS);

        assertThat(principal.role()).isEqualTo("editor");
        assertThat(principal.bypass()).isFalse();
    }

    @Test
    void tokenWithAllowedRoles_headerOutsideThem_isNotAllowed() {
        assertRoleNotAllowed(EDITOR_CAPABLE, "manager");
    }

    @Test
    void ordinaryToken_neverCarriesImpersonationHeaders() {
        Principal principal = RoleResolver.resolve(EDITOR_CAPABLE, "editor", IMPERSONATION_HEADERS);

        assertThat(principal.impersonationHeaders()).isEmpty();
    }

    @Test
    void anonToken_fromAPublishableKey_runsAsAnon() {
        JwtClaims publishable = token("anon", "public", Set.of("anon"));

        assertThat(RoleResolver.resolve(publishable, null, NO_HEADERS).role()).isEqualTo("anon");
    }

    // --- service tokens ---

    @Test
    void serviceToken_noHeader_bypasses() {
        Principal principal = RoleResolver.resolve(SERVICE, null, IMPERSONATION_HEADERS);

        assertThat(principal.role()).isEqualTo("service");
        assertThat(principal.bypass()).isTrue();
        assertThat(principal.impersonationHeaders()).isEmpty();
    }

    @Test
    void serviceToken_serviceHeader_bypasses() {
        Principal principal = RoleResolver.resolve(SERVICE, "service", NO_HEADERS);

        assertThat(principal.bypass()).isTrue();
    }

    @Test
    void serviceToken_headerNamingARole_runsAsThatRoleWithTheSessionHeaders() {
        Principal principal = RoleResolver.resolve(SERVICE, "user", IMPERSONATION_HEADERS);

        assertThat(principal.role()).isEqualTo("user");
        assertThat(principal.bypass()).isFalse();
        assertThat(principal.impersonationHeaders()).containsOnly(
                Map.entry("x-excalibase-user-id", "77"),
                Map.entry("x-excalibase-plan", "gold"));
    }

    @Test
    void serviceToken_mayActAsAnyValidRole() {
        assertThat(RoleResolver.resolve(SERVICE, "manager", NO_HEADERS).role()).isEqualTo("manager");
        assertThat(RoleResolver.resolve(SERVICE, "anon", NO_HEADERS).role()).isEqualTo("anon");
    }

    @Test
    void serviceToken_invalidRoleName_isNotAllowed() {
        assertRoleNotAllowed(SERVICE, "Not-A-Role");
    }

    @Test
    void serviceRoleWithoutServiceScope_neverBypasses() {
        JwtClaims forged = token("service", "public", Set.of("service"));

        assertThatThrownBy(() -> RoleResolver.resolve(forged, null, NO_HEADERS))
                .isInstanceOf(RoleNotAllowedException.class);
    }

    @Test
    void impersonationHeaders_ignoreAnythingThatIsNotASessionVariable() {
        Principal principal = RoleResolver.resolve(SERVICE, "user",
                Map.of("Authorization", "Bearer x", "x-excalibase-user-id", "5"));

        assertThat(principal.impersonationHeaders()).containsOnly(Map.entry("x-excalibase-user-id", "5"));
    }
}
