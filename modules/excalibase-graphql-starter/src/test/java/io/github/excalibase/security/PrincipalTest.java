package io.github.excalibase.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Session variables per docs/features/permissions.md §2. */
class PrincipalTest {

    private static final String PROJECT = "proj-a";

    private static JwtClaims passwordToken(Map<String, Object> rawClaims) {
        return new JwtClaims(String.valueOf(rawClaims.getOrDefault("userId", rawClaims.get("sub"))), PROJECT,
                "org", "app", "Org", (String) rawClaims.get("role"), (String) rawClaims.get("sub"),
                (String) rawClaims.get("scope"), 0L, rawClaims, Set.of((String) rawClaims.get("role")));
    }

    private static Map<String, Object> userClaims() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("sub", "alice@test.com");
        raw.put("userId", 42L);
        raw.put("role", "user");
        raw.put("projectId", PROJECT);
        raw.put("orgSlug", "org");
        raw.put("iss", "excalibase");
        raw.put("aud", List.of("excalibase:" + PROJECT));
        raw.put("exp", 4_000_000_000L);
        raw.put("iat", 1_700_000_000L);
        raw.put("nbf", 1_700_000_000L);
        raw.put("jti", "jti-1");
        raw.put("token_use", "access");
        return raw;
    }

    @Test
    void anonymous_carriesOnlyRoleAndProject() {
        Map<String, String> variables = Principal.anonymous().sessionVariables(PROJECT);

        assertThat(variables).containsOnly(
                Map.entry("x-excalibase-role", "anon"),
                Map.entry("x-excalibase-project-id", PROJECT));
    }

    @Test
    void signedInUser_carriesUserIdEmailAndEveryOtherClaim() {
        Map<String, Object> raw = userClaims();
        raw.put("plan", "gold");
        raw.put("Region", "eu");
        Principal principal = new Principal("user", false, passwordToken(raw), Map.of());

        Map<String, String> variables = principal.sessionVariables(PROJECT);

        assertThat(variables)
                .containsEntry("x-excalibase-role", "user")
                .containsEntry("x-excalibase-project-id", PROJECT)
                .containsEntry("x-excalibase-user-id", "42")
                .containsEntry("x-excalibase-email", "alice@test.com")
                .containsEntry("x-excalibase-plan", "gold")
                .containsEntry("x-excalibase-region", "eu")
                .containsEntry("x-excalibase-sub", "alice@test.com")
                .containsEntry("x-excalibase-orgslug", "org");
    }

    @Test
    void standardJwtClaimsAndTokenUse_areNotSessionVariables() {
        Principal principal = new Principal("user", false, passwordToken(userClaims()), Map.of());

        assertThat(principal.sessionVariables(PROJECT).keySet()).isNotEmpty().doesNotContain(
                "x-excalibase-iss", "x-excalibase-aud", "x-excalibase-exp", "x-excalibase-iat",
                "x-excalibase-nbf", "x-excalibase-jti", "x-excalibase-token_use",
                "x-excalibase-userid", "x-excalibase-projectid");
    }

    @Test
    void activeRole_winsOverTheRoleClaim() {
        Map<String, Object> raw = userClaims();
        raw.put("allowed_roles", List.of("user", "editor"));
        Principal principal = new Principal("editor", false, passwordToken(raw), Map.of());

        assertThat(principal.sessionVariables(PROJECT)).containsEntry("x-excalibase-role", "editor");
    }

    @Test
    void userId_comesOnlyFromTheUserIdClaim_neverFromSub() {
        Map<String, Object> raw = userClaims();
        raw.remove("userId");
        Principal principal = new Principal("user", false, passwordToken(raw), Map.of());

        assertThat(principal.sessionVariables(PROJECT)).doesNotContainKey("x-excalibase-user-id");
    }

    @Test
    void email_prefersTheEmailClaimOverSub() {
        Map<String, Object> raw = userClaims();
        raw.put("email", "real@test.com");
        Principal principal = new Principal("user", false, passwordToken(raw), Map.of());

        assertThat(principal.sessionVariables(PROJECT)).containsEntry("x-excalibase-email", "real@test.com");
    }

    @Test
    void apiKeyToken_doesNotTurnSubIntoAnEmail() {
        Map<String, Object> raw = new HashMap<>(Map.of(
                "sub", "apikey:7", "role", "anon", "scope", "public", "keyId", 7L));
        Principal principal = new Principal("anon", false, passwordToken(raw), Map.of());

        assertThat(principal.sessionVariables(PROJECT)).doesNotContainKey("x-excalibase-email");
    }

    @Test
    void arrayClaim_isRenderedAsAnArrayLiteral() {
        Map<String, Object> raw = userClaims();
        raw.put("teams", List.of("red", "blue \"b\""));
        Principal principal = new Principal("user", false, passwordToken(raw), Map.of());

        assertThat(principal.sessionVariables(PROJECT))
                .containsEntry("x-excalibase-teams", "{\"red\",\"blue \\\"b\\\"\"}");
    }

    @Test
    void impersonation_takesSessionVariablesFromTheHeadersNotTheServiceToken() {
        Map<String, Object> raw = new HashMap<>(Map.of(
                "sub", "apikey:9", "role", "service", "scope", "service", "keyId", 9L, "userId", 1L));
        Principal principal = new Principal("user", false, passwordToken(raw),
                Map.of("X-Excalibase-User-Id", "77", "x-excalibase-plan", "gold"));

        assertThat(principal.sessionVariables(PROJECT)).containsOnly(
                Map.entry("x-excalibase-role", "user"),
                Map.entry("x-excalibase-project-id", PROJECT),
                Map.entry("x-excalibase-user-id", "77"),
                Map.entry("x-excalibase-plan", "gold"));
    }

    @Test
    void sessionVariables_areImmutable() {
        Map<String, String> variables = Principal.anonymous().sessionVariables(PROJECT);

        assertThatThrownBy(() -> variables.put("x-excalibase-role", "service"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void bypass_isHeldByTheServiceRoleAndNothingElse() {
        assertThatThrownBy(() -> new Principal("user", true, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Principal(Principal.SERVICE, false, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roleName_mustBeALowercaseIdentifier() {
        assertThat(Principal.isValidRoleName("editor_2")).isTrue();
        assertThat(Principal.isValidRoleName("Editor")).isFalse();
        assertThat(Principal.isValidRoleName(null)).isFalse();
        assertThatThrownBy(() -> new Principal("Bad-Role", false, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
