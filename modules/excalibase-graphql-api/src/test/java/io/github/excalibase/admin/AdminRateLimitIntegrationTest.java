package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** A secret that is missing, wrong or almost right is a 401, and too many of them from one address is a 429. */
class AdminRateLimitIntegrationTest extends EngineIntegrationTest {

    private static final int ADMIN_PORT = FreePort.find();
    private static final int MAX_FAILURES = 4;
    private static final String PATH = "/_admin/v1/metadata";

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = AdminTestEngine.consoleOn(
                AdminTestEngine.documentFile(AdminTestEngine.DOCUMENT), -1, ADMIN_PORT);
        properties.put("excalibase.admin.max-failures", Integer.toString(MAX_FAILURES));
        properties.put("excalibase.admin.failure-window-seconds", "600");
        return properties;
    }

    @Test
    @DisplayName("missing, wrong and almost-right secrets are 401, then the address is limited, even for the right one")
    void unauthorizedThenLimited() {
        assertThat(admin.get(PATH).status()).isEqualTo(401);
        assertThat(admin.get(PATH, HEADER, "wrong").status()).isEqualTo(401);
        assertThat(admin.get(PATH, HEADER, SECRET.substring(0, SECRET.length() - 1) + "X").status()).isEqualTo(401);
        HttpAnswer fourth = admin.get(PATH, HEADER, SECRET + "x");
        assertThat(fourth.status()).isEqualTo(401);
        assertThat(fourth.body()).doesNotContain(SECRET);

        HttpAnswer limited = admin.get(PATH, HEADER, SECRET);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.header("Retry-After")).isNotBlank();
        assertThat(limited.body()).doesNotContain(SECRET);
        assertThat(admin.get(PATH).status()).isEqualTo(429);
    }
}
