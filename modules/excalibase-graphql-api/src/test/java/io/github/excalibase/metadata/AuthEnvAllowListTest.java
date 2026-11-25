package io.github.excalibase.metadata;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A name that is not in the allow-list cannot silently do nothing: start (and validate) refuse it. */
class AuthEnvAllowListTest {

    private static final Set<String> NO_NAMES = Set.of();

    @Test
    void anUnknownEnvironmentName_refusesStart() {
        for (String name : List.of("APP_AUTH_CLAIM_ROLE", "APP_AUTH_AUDIENCES", "APP_AUTH_", "APP_AUTH_HMAC_SECRET")) {
            Map<String, String> env = Map.of(name, "x");

            assertThatThrownBy(() -> AuthSettingsReader.fromEnv(env))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(name)
                    .hasMessageContaining("APP_AUTH_CLAIMS_ROLE");
        }
    }

    @Test
    void aLegacyOrOtherName_isNotTouched() {
        Map<String, String> others = Map.of("APP_SECURITY_AUTH_JWKS_URL", "x", "APP_AUTHOR", "y", "AUTH_AUD_PREFIX", "p");

        assertThatCode(() -> AuthSettingsReader.fromEnv(others)).doesNotThrowAnyException();
    }

    @Test
    void anUnknownPropertyKey_refusesStart() {
        for (String key : AuthSpellings.NEVER_READ) {
            Set<String> keys = Set.of(key);

            assertThatThrownBy(() -> AuthSettingsReader.refuseUnknown(NO_NAMES, keys))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.auth." + key);
        }
    }

    @Test
    void theSpellingsMicronautReads_areAccepted() {
        for (String key : AuthSpellings.READ) {
            Set<String> keys = Set.of(key);

            assertThatCode(() -> AuthSettingsReader.refuseUnknown(NO_NAMES, keys)).doesNotThrowAnyException();
        }
    }

    @Test
    void aLowerCaseEnvironmentName_isCheckedLikeTheUpperCaseOne() {
        for (String name : List.of("app_auth_claim_role", "App_Auth_Audiences", "app_auth_")) {
            Set<String> names = Set.of(name);
            Map<String, String> env = Map.of(name, "x");

            assertThatThrownBy(() -> AuthSettingsReader.refuseUnknown(names, NO_NAMES))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(name);
            assertThatThrownBy(() -> AuthSettingsReader.fromEnv(env)).isInstanceOf(IllegalStateException.class);
        }
        Set<String> known = Set.of("app_auth_secret");

        assertThatCode(() -> AuthSettingsReader.refuseUnknown(known, NO_NAMES)).doesNotThrowAnyException();
    }

    @Test
    void aLowerCaseKnownName_isReadAsTheSameSetting() {
        EngineAuth auth = AuthSettingsReader.fromEnv(Map.of("app_auth_secret", AuthSettingsReaderTest.SECRET,
                "app_auth_audience", "api"));

        assertThat(auth.engine()).isTrue();
    }

    @Test
    void aStrayLowerCaseName_inHostedMode_isRefusedAsHosted() {
        AuthSettingsReader.Inputs stray = new AuthSettingsReader.Inputs(true, true, true, Set.of("app_auth_stray"),
                NO_NAMES, key -> null);

        assertThatThrownBy(() -> AuthSettingsReader.resolve(stray)).hasMessage(AuthSettingsReader.HOSTED_REFUSAL);
    }

    @Test
    void aMutationThatDropsTheCheck_wouldBeSeen() {
        // the allow-list is the only thing between a typo and a silently missing audience
        Map<String, String> typo = Map.of("APP_AUTH_SECRET", AuthSettingsReaderTest.SECRET, "APP_AUTH_AUDIENCE", "api",
                "APP_AUTH_ALGORITHM", "HS512");

        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(typo)).hasMessageContaining("APP_AUTH_ALGORITHM");
        assertThat(AuthSettingsReader.ENV_NAMES).doesNotContain("APP_AUTH_ALGORITHM");
    }

    @Test
    void theSameSettingInTwoCases_isRefused_soInsertionOrderNeverDecidesWhichWins() {
        Map<String, String> both = new LinkedHashMap<>();
        both.put("APP_AUTH_SECRET", AuthSettingsReaderTest.SECRET);
        both.put("app_auth_secret", "another-hmac-secret-of-at-least-32-bytes");
        both.put("APP_AUTH_AUDIENCE", "api");

        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(both)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AUTH_SECRET").hasMessageContaining("app_auth_secret")
                .hasMessageContaining("two cases").hasMessageNotContaining(AuthSettingsReaderTest.SECRET);
        Set<String> sameIssuer = Set.of("APP_AUTH_ISSUER", "App_Auth_Issuer");

        assertThatThrownBy(() -> AuthSettingsReader.refuseUnknown(sameIssuer, NO_NAMES)).hasMessageContaining("two cases");
    }
}
