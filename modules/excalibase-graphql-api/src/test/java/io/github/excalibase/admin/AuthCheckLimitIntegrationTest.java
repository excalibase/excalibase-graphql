package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** "Test a token" has a limit of its own, which a correct admin secret does not lift. */
class AuthCheckLimitIntegrationTest extends EngineIntegrationTest {

    private static final int ADMIN_PORT = FreePort.find();
    private static final Path FILE = AdminTestEngine.documentFile(DOCUMENT);
    private static final int MAX_CHECKS = 5;

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = AdminTestEngine.authPageOn(FILE, -1, ADMIN_PORT);
        properties.put("excalibase.admin.token-check-limit", Integer.toString(MAX_CHECKS));
        properties.put("excalibase.admin.token-check-window-seconds", "600");
        return properties;
    }

    private HttpAnswer check() {
        return admin.post("/_admin/v1/auth/check", "x.y.z", HEADER, SECRET);
    }

    @Test
    @DisplayName("past the limit the checks are 429 with Retry-After, though the admin secret is right every time")
    void limited() {
        for (int attempt = 0; attempt < MAX_CHECKS; attempt++) {
            assertThat(check().status()).isEqualTo(200);
        }
        HttpAnswer last = check();

        assertThat(last.status()).isEqualTo(429);
        assertThat(last.header("Retry-After")).isNotBlank();
        assertThat(admin.get("/_admin/v1/auth", HEADER, SECRET).status()).isEqualTo(200);
    }
}
