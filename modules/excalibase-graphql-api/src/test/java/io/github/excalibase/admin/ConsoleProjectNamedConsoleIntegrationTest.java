package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;

/** A project may be called console: the console being on must not take its public API away. */
class ConsoleProjectNamedConsoleIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final int ADMIN_PORT = FreePort.find();

    private final EngineClient management = AdminTestEngine.on(MANAGEMENT_PORT);
    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = AdminTestEngine.consoleOn(
                AdminTestEngine.documentFile(DOCUMENT.replace(AdminTestEngine.PROJECT, "console")),
                MANAGEMENT_PORT, ADMIN_PORT);
        properties.put("app.project-id", "console");
        return properties;
    }

    @Test
    @DisplayName("the project's public GraphQL path is routed to the project, not refused as the console")
    void publicProjectPathWorks() {
        String body = http.graphql("/console/graphql", "{ __typename }").body();

        assertThat(body).doesNotContain("\"path\":\"/console/graphql\"").doesNotContain("Not Found");
    }

    @Test
    @DisplayName("the console's assets are still a 404 on the public and management ports")
    void assetsStayOnTheAdminPort() {
        for (EngineClient other : List.of(http, management)) {
            assertThat(other.get("/console/app.js").status()).isEqualTo(404);
            assertThat(other.get("/console").status()).isEqualTo(404);
        }
        assertThat(admin.get("/console/app.js").status()).isEqualTo(200);
    }
}
