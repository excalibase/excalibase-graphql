package io.github.excalibase.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AdminConfigTest {

    private static final String SECRET = "config-secret-0123456789-abcdefghijklmnop";
    private final AdminConfig config = new AdminConfig();

    @Test
    void neitherUrlIsNotHosted() {
        assertThat(config.hosted("", "").active()).isFalse();
        assertThat(config.hosted(" ", " ").active()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True"})
    void trueInAnyCaseIsOn(String spelling) {
        assertThat(config.adminSettings(spelling, 10001, SECRET, "", 10000, 8081, new AdminConfig.Hosted(false))
                .enabled()).isTrue();
    }
}
