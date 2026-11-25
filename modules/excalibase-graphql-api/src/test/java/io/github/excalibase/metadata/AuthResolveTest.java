package io.github.excalibase.metadata;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What start-up decides about the engine's own token settings, from what the environment and properties hold. */
class AuthResolveTest {

    static final String HOSTED_REFUSAL = "app.auth.* (APP_AUTH_*) is refused in hosted mode "
            + "(app.security.rls.policy-url or app.security.multi-tenant.provisioning-url is set): one engine-wide "
            + "verifier would bypass the per-project audience check";

    private static final Map<String, String> VALUES = Map.of("secret", AuthSettingsReaderTest.SECRET,
            "audience", "my-api");
    private static final UnaryOperator<String> BY_KEY = VALUES::get;

    private static AuthSettingsReader.Inputs inputs(boolean hosted, boolean jwtEnabled, boolean legacy,
                                                    Set<String> env, Set<String> keys, UnaryOperator<String> values) {
        return new AuthSettingsReader.Inputs(hosted, jwtEnabled, legacy, env, keys, values);
    }

    @Test
    void nothingSet_isNoEngineSettings_inEveryMode() {
        UnaryOperator<String> none = key -> "";
        assertThat(AuthSettingsReader.resolve(inputs(false, true, false, Set.of(), Set.of("secret", "audience"), none))
                .engine()).isFalse();
        assertThat(AuthSettingsReader.resolve(inputs(true, true, true, Set.of(), Set.of("secret"), none)).legacy())
                .isTrue();
        assertThat(AuthSettingsReader.resolve(inputs(false, false, false, Set.of(), Set.of("secret"), none))
                .configured()).isFalse();
    }

    @Test
    void settingsInTheEngine_resolveToEngineAuth() {
        EngineAuth auth = AuthSettingsReader.resolve(inputs(false, true, false, Set.of("APP_AUTH_SECRET"),
                Set.of("secret", "audience"), BY_KEY));

        assertThat(auth.engine()).isTrue();
        assertThat(auth.legacy()).isFalse();
    }

    @Test
    void hostedMode_refusesAnyAppAuthName_withTheExactMessage() {
        AuthSettingsReader.Inputs strayName = inputs(true, true, true, Set.of("APP_AUTH_STRAY"), Set.of(), key -> "");
        AuthSettingsReader.Inputs knownKeys = inputs(true, true, false, Set.of(), Set.of("secret", "audience"), BY_KEY);
        AuthSettingsReader.Inputs strayKey = inputs(true, true, false, Set.of(), Set.of("claim.role"), key -> "");

        assertThatThrownBy(() -> AuthSettingsReader.resolve(strayName)).isInstanceOf(IllegalStateException.class)
                .hasMessage(HOSTED_REFUSAL);
        assertThatThrownBy(() -> AuthSettingsReader.resolve(knownKeys)).hasMessage(HOSTED_REFUSAL);
        assertThatThrownBy(() -> AuthSettingsReader.resolve(strayKey)).hasMessage(HOSTED_REFUSAL);
    }

    @Test
    void appAuthWithAuthenticationOff_refusesStart() {
        AuthSettingsReader.Inputs authOff = inputs(false, false, false, Set.of(), Set.of("secret", "audience"), BY_KEY);

        assertThatThrownBy(() -> AuthSettingsReader.resolve(authOff)).isInstanceOf(IllegalStateException.class)
                .hasMessage("app.auth.* is set but app.security.jwt-enabled is false: the settings would do nothing; "
                        + "remove them or turn authentication on");
    }

    @Test
    void appAuthBesideLegacySettings_refusesStart() {
        AuthSettingsReader.Inputs both = inputs(false, true, true, Set.of(), Set.of("secret", "audience"), BY_KEY);

        assertThatThrownBy(() -> AuthSettingsReader.resolve(both)).isInstanceOf(IllegalStateException.class)
                .hasMessage("app.auth.* and app.security.auth.* are both set: configure one token source, not two");
    }

    @Test
    void anUnknownNameRefusesStart_beforeAnythingIsBuilt() {
        AuthSettingsReader.Inputs unknownEnv = inputs(false, true, false, Set.of("APP_AUTH_CLAIM_ROLE"), Set.of(),
                key -> "");
        AuthSettingsReader.Inputs unknownKey = inputs(false, true, false, Set.of(), Set.of("claim.role"), key -> "");

        assertThatThrownBy(() -> AuthSettingsReader.resolve(unknownEnv)).hasMessageContaining("APP_AUTH_CLAIM_ROLE");
        assertThatThrownBy(() -> AuthSettingsReader.resolve(unknownKey)).hasMessageContaining("app.auth.claim.role");
    }
}
