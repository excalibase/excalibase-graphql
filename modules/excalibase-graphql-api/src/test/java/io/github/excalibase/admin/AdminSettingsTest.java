package io.github.excalibase.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminSettingsTest {

    private static final String SECRET = "settings-secret-0123456789-abcdefghijklm";

    private static AdminSettings.Raw on(String secret, String secretFile) {
        return new AdminSettings.Raw(true, 10001, secret, secretFile, 10000, 8081, false);
    }

    @Test
    void theConsoleIsOffByDefaultAndNeedsNoSecret() {
        AdminSettings settings = AdminSettings.resolve(new AdminSettings.Raw(false, 10001, "", "", 10000, 8081, false));

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.secret()).isNull();
    }

    @Test
    void offStaysOffWhateverElseIsSet() {
        AdminSettings settings = AdminSettings.resolve(new AdminSettings.Raw(false, 0, "short", "", 10000, 8081, true));

        assertThat(settings.enabled()).isFalse();
    }

    @Test
    void onWithAGoodSecretIsEnabledOnTheAdminPort() {
        AdminSettings settings = AdminSettings.resolve(on(SECRET, ""));

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.port()).isEqualTo(10001);
        assertThat(settings.secret().matches(SECRET)).isTrue();
    }

    @Test
    void onWithoutASecretRefusesToStart() {
        assertThatThrownBy(() -> AdminSettings.resolve(on("", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXCALIBASE_ADMIN_SECRET");
    }

    @Test
    void onWithAShortSecretRefusesToStartWithoutEchoingIt() {
        String shortSecret = "only-thirty-one-characters-long";
        assertThat(shortSecret).hasSize(AdminSecret.MIN_LENGTH - 1);

        assertThatThrownBy(() -> AdminSettings.resolve(on(shortSecret, "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32")
                .hasMessageNotContaining(shortSecret);
    }

    @Test
    void theSecretCanComeFromAFile(@TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("admin-secret"), SECRET + "\n");

        AdminSettings settings = AdminSettings.resolve(on("", file.toString()));

        assertThat(settings.secret().matches(SECRET)).isTrue();
    }

    @Test
    void aShortSecretInAFileRefusesToStart(@TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("admin-secret"), "tiny\n");

        assertThatThrownBy(() -> AdminSettings.resolve(on("", file.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void anUnreadableSecretFileRefusesToStartAndNamesTheFile(@TempDir Path directory) {
        Path missing = directory.resolve("nope");

        assertThatThrownBy(() -> AdminSettings.resolve(on("", missing.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXCALIBASE_ADMIN_SECRET_FILE")
                .hasMessageContaining(missing.toString());
    }

    @Test
    void aSecretAndASecretFileTogetherAreRefused(@TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("admin-secret"), SECRET);

        assertThatThrownBy(() -> AdminSettings.resolve(on(SECRET, file.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not both")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void hostedModeRefusesTheConsole() {
        assertThatThrownBy(() -> AdminSettings.resolve(new AdminSettings.Raw(true, 10001, SECRET, "", 10000, 8081, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXCALIBASE_CONSOLE_ENABLED")
                .hasMessageContaining("hosted")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void thePortMustBeUsableAndDistinctFromTheOtherListeners() {
        assertThatThrownBy(() -> AdminSettings.resolve(new AdminSettings.Raw(true, 0, SECRET, "", 10000, 8081, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EXCALIBASE_ADMIN_PORT");
        assertThatThrownBy(() -> AdminSettings.resolve(new AdminSettings.Raw(true, 70000, SECRET, "", 10000, 8081, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EXCALIBASE_ADMIN_PORT");
        assertThatThrownBy(() -> AdminSettings.resolve(new AdminSettings.Raw(true, 10000, SECRET, "", 10000, 8081, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("public port");
        assertThatThrownBy(() -> AdminSettings.resolve(new AdminSettings.Raw(true, 8081, SECRET, "", 10000, 8081, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("management port");
    }

    @Test
    void toStringNeverShowsTheSecret() {
        assertThat(AdminSettings.resolve(on(SECRET, "")).toString()).doesNotContain(SECRET);
    }
}
