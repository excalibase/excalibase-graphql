package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** A history that cannot be written never blocks a save, and the answer says so. */
class AdminHistorySkippedIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final int ADMIN_PORT = FreePort.find();
    private static final Path FILE = prepared();
    private static final String METADATA = "/_admin/v1/metadata";

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    private static Path prepared() {
        Path file = AdminTestEngine.documentFile(DOCUMENT);
        try {
            Files.createDirectory(file.resolveSibling("permissions.history"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return file;
    }

    @Override
    public Map<String, String> getProperties() {
        return AdminTestEngine.consoleOn(FILE, MANAGEMENT_PORT, ADMIN_PORT);
    }

    @Test
    @DisplayName("a save with an unwritable history is 200, writes the rules, and says historyRecorded is false")
    void saveSaysSo() throws IOException {
        String version = admin.get(METADATA, HEADER, SECRET).jsonPath("$.sourceVersion");
        String bigger = "# more\n" + DOCUMENT;

        HttpAnswer answer = admin.send("PUT", METADATA, bigger, HEADER, SECRET, "Content-Type", "text/plain",
                "If-Match", "\"" + version + "\"");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.<Boolean>jsonPath("$.historyRecorded")).isFalse();
        assertThat(Files.readString(FILE)).isEqualTo(bigger);
    }
}
