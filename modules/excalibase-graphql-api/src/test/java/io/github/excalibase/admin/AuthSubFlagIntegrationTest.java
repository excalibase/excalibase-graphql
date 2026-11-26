package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** With the sub-flag off (the default) the Auth calls do not exist, secret or not, and the rest of the admin API is untouched. */
class AuthSubFlagIntegrationTest extends EngineIntegrationTest {

    private static final int ADMIN_PORT = FreePort.find();
    private static final Path FILE = AdminTestEngine.documentFile(DOCUMENT);

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = AdminTestEngine.authPageOn(FILE, -1, ADMIN_PORT);
        properties.remove("console.auth-page.enabled");
        return properties;
    }

    @Test
    @DisplayName("GET /auth and POST /auth/check are a 404 with the console on and the sub-flag off")
    void off() {
        assertThat(admin.get("/_admin/v1/auth", HEADER, SECRET).status()).isEqualTo(404);
        assertThat(admin.post("/_admin/v1/auth/check", "x.y.z", HEADER, SECRET).status()).isEqualTo(404);
        assertThat(admin.get("/_admin/v1/metadata", HEADER, SECRET).status()).isEqualTo(200);
    }
}
