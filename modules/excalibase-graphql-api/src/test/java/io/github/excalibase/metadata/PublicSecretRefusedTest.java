package io.github.excalibase.metadata;

import io.github.excalibase.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The placeholder secret the examples used to ship is public, so the engine refuses to sign or check with it. */
class PublicSecretRefusedTest {

    private static final String PUBLIC_SECRET = "change-me-to-a-long-random-secret-of-32-bytes-or-more";
    private static final String REAL_SECRET = "k9Qw3rT7yU1iO5pA8sD2fG6hJ4lZ0xCv";

    @Test
    @DisplayName("APP_AUTH_SECRET holding the example placeholder is refused at startup")
    void engineSecretRefused() {
        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(Map.of("APP_AUTH_SECRET", PUBLIC_SECRET)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("change-me");
    }

    @Test
    @DisplayName("a placeholder in any case, or inside a longer value, is refused too")
    void variantsRefused() {
        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(Map.of("APP_AUTH_SECRET", "x-CHANGE-ME-" + REAL_SECRET)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("change-me");
    }

    @Test
    @DisplayName("a secret file holding the placeholder is refused")
    void secretFileRefused(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("secret"), PUBLIC_SECRET);

        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(Map.of("APP_AUTH_SECRET_FILE", file.toString())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("change-me");
    }

    @Test
    @DisplayName("a document's jwt: secret reference holding the placeholder is refused")
    void referencedSecretRefused() {
        assertThatThrownBy(() -> JwtKeyLoader.secret("env:SECRET", name -> PUBLIC_SECRET))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("change-me");
    }

    @Test
    @DisplayName("the older hmac-secret setting is refused with the placeholder")
    void legacyHmacRefused() {
        assertThatThrownBy(() -> new JwtService(PUBLIC_SECRET))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("change-me");
    }

    @Test
    @DisplayName("a real random secret is accepted everywhere")
    void realSecretAccepted() {
        assertThat(AuthSettingsReader.fromEnv(Map.of("APP_AUTH_SECRET", REAL_SECRET, "APP_AUTH_AUDIENCE", "my-app-api"))
                .engine()).isTrue();
        assertThat(JwtKeyLoader.secret("env:SECRET", name -> REAL_SECRET)).hasSize(REAL_SECRET.length());
        assertThat(new JwtService(REAL_SECRET)).isNotNull();
    }
}
