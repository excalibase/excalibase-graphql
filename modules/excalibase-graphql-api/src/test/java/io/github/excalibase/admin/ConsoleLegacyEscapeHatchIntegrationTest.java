package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;

/** EXCALIBASE_CONSOLE_UI=legacy: the escape hatch. /console is the plain-script console even though the jar holds the built one. */
class ConsoleLegacyEscapeHatchIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final int ADMIN_PORT = FreePort.find();

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = AdminTestEngine.consoleOn(AdminTestEngine.documentFile(DOCUMENT),
                MANAGEMENT_PORT, ADMIN_PORT);
        properties.put("excalibase.console.ui", "legacy");
        return properties;
    }

    @Test
    @DisplayName("/console is the old page, under the old policy with no nonce and no font allowance")
    void theOldConsoleAnswers() {
        HttpAnswer page = admin.get("/console");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("/console/app.js");
        assertThat(page.header("Content-Security-Policy")).contains("style-src 'self';").doesNotContain("nonce", "font-src");
    }

    @Test
    @DisplayName("the built console's files and the /console/legacy alias are not served")
    void theBuiltConsoleIsOff() {
        assertThat(admin.get("/console/assets/index-abc123.js").status()).isEqualTo(404);
        assertThat(admin.get("/console/legacy").status()).isEqualTo(404);
    }
}
