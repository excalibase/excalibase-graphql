package io.github.excalibase.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSettingsTest {

    private static final String SECRET = "settings-secret-0123456789-abcdefghijklm";
    private static final int PUBLIC_PORT = 10000;
    private static final int MANAGEMENT_PORT = 8081;

    private static AdminSettings.Raw on(String secret, String secretFile) {
        return new AdminSettings.Raw(true, 10001, secret, secretFile, PUBLIC_PORT, MANAGEMENT_PORT, false);
    }

    private static AdminSettings.Raw onPort(int port) {
        return new AdminSettings.Raw(true, port, SECRET, "", PUBLIC_PORT, MANAGEMENT_PORT, false);
    }

    /** The message the engine stops with; fails when it does not refuse. */
    private static String refusal(AdminSettings.Raw raw) {
        try {
            AdminSettings.resolve(raw);
        } catch (IllegalStateException refused) {
            return refused.getMessage();
        }
        throw new AssertionError("the settings were accepted");
    }

    @Test
    void theConsoleIsOffByDefaultAndNeedsNoSecret() {
        AdminSettings settings = AdminSettings.resolve(
                new AdminSettings.Raw(false, 10001, "", "", PUBLIC_PORT, MANAGEMENT_PORT, false));

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.secret()).isNull();
    }

    @Test
    void offStaysOffWhateverElseIsSet() {
        AdminSettings settings = AdminSettings.resolve(
                new AdminSettings.Raw(false, 0, "short", "", PUBLIC_PORT, MANAGEMENT_PORT, true));

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
        assertThat(refusal(on("", ""))).contains("EXCALIBASE_ADMIN_SECRET");
    }

    @Test
    void onWithAShortSecretRefusesToStartWithoutEchoingIt() {
        String shortSecret = "only-thirty-one-characters-long";
        assertThat(shortSecret).hasSize(AdminSecret.MIN_LENGTH - 1);

        assertThat(refusal(on(shortSecret, ""))).contains("at least 32").doesNotContain(shortSecret);
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

        assertThat(refusal(on("", file.toString()))).contains("at least 32");
    }

    @Test
    void anUnreadableSecretFileRefusesToStartAndNamesTheFile(@TempDir Path directory) {
        Path missing = directory.resolve("nope");

        assertThat(refusal(on("", missing.toString())))
                .contains("EXCALIBASE_ADMIN_SECRET_FILE").contains(missing.toString());
    }

    @Test
    void aSecretAndASecretFileTogetherAreRefused(@TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("admin-secret"), SECRET);

        assertThat(refusal(on(SECRET, file.toString()))).contains("not both").doesNotContain(SECRET);
    }

    @Test
    void hostedModeRefusesTheConsole() {
        AdminSettings.Raw hosted = new AdminSettings.Raw(true, 10001, SECRET, "", PUBLIC_PORT, MANAGEMENT_PORT, true);

        assertThat(refusal(hosted)).contains("EXCALIBASE_CONSOLE_ENABLED").contains("hosted").doesNotContain(SECRET);
    }

    @Test
    void thePortMustBeUsable() {
        assertThat(refusal(onPort(0))).contains("EXCALIBASE_ADMIN_PORT");
        assertThat(refusal(onPort(70000))).contains("EXCALIBASE_ADMIN_PORT");
    }

    @Test
    void thePortMustDifferFromThePublicAndManagementPorts() {
        assertThat(refusal(onPort(PUBLIC_PORT))).contains("public port");
        assertThat(refusal(onPort(MANAGEMENT_PORT))).contains("management port");
    }

    @Test
    void toStringNeverShowsTheSecret() {
        assertThat(AdminSettings.resolve(on(SECRET, "")).toString()).doesNotContain(SECRET);
    }
}
