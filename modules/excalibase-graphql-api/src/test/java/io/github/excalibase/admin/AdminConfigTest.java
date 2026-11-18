package io.github.excalibase.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AdminConfigTest {

    private static final String SECRET = "config-secret-0123456789-abcdefghijklmnop";
    private final AdminConfig config = new AdminConfig();

    private String refusal(String enabled, AdminConfig.Hosted hosted) {
        try {
            config.adminSettings(enabled, 10001, SECRET, "", 10000, 8081, hosted);
        } catch (IllegalStateException refused) {
            return refused.getMessage();
        }
        throw new AssertionError("the settings were accepted");
    }

    @Test
    void aPolicyUrlAloneMakesTheDeploymentHostedAndRefusesTheConsole() {
        AdminConfig.Hosted hosted = config.hosted("http://control-plane/policies", "");

        assertThat(hosted.active()).isTrue();
        assertThat(refusal("true", hosted)).contains("EXCALIBASE_CONSOLE_ENABLED").contains("hosted");
    }

    @Test
    void aProvisioningUrlAloneMakesTheDeploymentHostedAndRefusesTheConsole() {
        AdminConfig.Hosted hosted = config.hosted("", "http://control-plane/api");

        assertThat(hosted.active()).isTrue();
        assertThat(refusal("true", hosted)).contains("EXCALIBASE_CONSOLE_ENABLED").contains("hosted");
    }

    @Test
    void neitherUrlIsNotHosted() {
        assertThat(config.hosted("", "").active()).isFalse();
        assertThat(config.hosted(" ", " ").active()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "1", "on", "", " true", "enabled"})
    void anySpellingButTrueOrFalseRefusesToStart(String spelling) {
        assertThat(refusal(spelling, new AdminConfig.Hosted(false)))
                .contains("EXCALIBASE_CONSOLE_ENABLED must be true or false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True"})
    void trueInAnyCaseIsOn(String spelling) {
        assertThat(config.adminSettings(spelling, 10001, SECRET, "", 10000, 8081, new AdminConfig.Hosted(false))
                .enabled()).isTrue();
    }
}
