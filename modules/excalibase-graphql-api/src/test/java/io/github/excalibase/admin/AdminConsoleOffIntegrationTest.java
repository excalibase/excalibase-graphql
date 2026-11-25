package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console flag is not set. Even with a port and a secret configured, no admin listener exists and
 * {@code /_admin/**} is a 404 on every port.
 */
class AdminConsoleOffIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final int ADMIN_PORT = FreePort.find();
    private static final String PATH = "/_admin/v1/metadata";

    private final EngineClient management = AdminTestEngine.on(MANAGEMENT_PORT);

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = AdminTestEngine.properties(
                AdminTestEngine.documentFile(AdminTestEngine.DOCUMENT), MANAGEMENT_PORT);
        properties.put("excalibase.admin.port", Integer.toString(ADMIN_PORT));
        properties.put("excalibase.admin.secret", SECRET);
        return properties;
    }

    @Test
    @DisplayName("nothing listens on the admin port")
    void noAdminListener() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", ADMIN_PORT), 500);
            throw new AssertionError("something listens on the admin port while the console is off");
        } catch (IOException expected) {
            assertThat(expected).isNotNull();
        }
    }

    @Test
    @DisplayName("/_admin is a 404 on the public port and on the management port")
    void adminPathsAreNotFound() {
        assertThat(http.get(PATH).status()).isEqualTo(404);
        assertThat(http.get(PATH, HEADER, SECRET).status()).isEqualTo(404);
        assertThat(management.get(PATH).status()).isEqualTo(404);
        assertThat(management.get(PATH, HEADER, SECRET).status()).isEqualTo(404);
        assertThat(http.get("/_admin").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("/console is a 404 on every port, and nothing is served")
    void consoleIsNotFound() {
        assertThat(http.get("/console").status()).isEqualTo(404);
        assertThat(http.get("/console/app.js").status()).isEqualTo(404);
        assertThat(management.get("/console").status()).isEqualTo(404);
        assertThat(management.get("/console/app.js", HEADER, SECRET).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the ordinary surfaces are unaffected")
    void restOfTheEngineIsUnchanged() {
        assertThat(management.get("/actuator/health").status()).isIn(200, 503);
    }
}
