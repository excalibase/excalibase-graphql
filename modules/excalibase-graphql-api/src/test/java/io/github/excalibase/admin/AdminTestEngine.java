package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineProperties;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** The settings of an engine with the console on: a file metadata source, token settings of its own, three ports. */
final class AdminTestEngine {

    static final String PROJECT = "proj-admin";
    static final String SECRET = "admin-secret-0123456789-abcdefghijklmnop";
    static final String HEADER = "X-Excalibase-Admin-Secret";
    static final String DOCUMENT = """
            format: 1
            projectId: proj-admin
            tables:
              - table: public.orders
                role: user
                select:
                  filter: {}
                  columns: "*"
            """;

    private AdminTestEngine() {
    }

    static Path documentFile(String text) {
        try {
            Path file = Files.createTempDirectory("admin-it").resolve("permissions.yaml");
            Files.writeString(file, text);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The engine with every surface on; the caller adds {@code excalibase.console.enabled} and the admin port. */
    static Map<String, String> properties(Path document, int managementPort) {
        Map<String, String> properties = EngineProperties.unreachableDatabase(PROJECT);
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.security.insecure-dev-mode", "false");
        properties.put("app.security.auth.hmac-secret", "an-hmac-secret-of-at-least-32-bytes!!");
        properties.put("metadata.source", "file");
        properties.put("metadata.file.path", document.toString());
        properties.put("metadata.file.poll-interval-ms", "50");
        properties.put("endpoints.all.port", Integer.toString(managementPort));
        return properties;
    }

    static Map<String, String> consoleOn(Path document, int managementPort, int adminPort) {
        Map<String, String> properties = properties(document, managementPort);
        properties.put("excalibase.console.enabled", "true");
        properties.put("excalibase.admin.port", Integer.toString(adminPort));
        properties.put("excalibase.admin.secret", SECRET);
        return properties;
    }

    /** The console with the Auth page on and the engine's own token settings ({@code app.auth.*}) in place of the legacy ones. */
    static Map<String, String> authPageOn(Path document, int managementPort, int adminPort) {
        Map<String, String> properties = consoleOn(document, managementPort, adminPort);
        properties.remove("app.security.auth.hmac-secret");
        properties.put("console.auth-page.enabled", "true");
        properties.put("app.auth.secret", AuthTokens.SECRET);
        properties.put("app.auth.audience", AuthTokens.AUDIENCE);
        properties.put("app.auth.issuer", AuthTokens.ISSUER);
        return properties;
    }

    static EngineClient on(int port) {
        return new EngineClient(URI.create("http://localhost:" + port + "/"));
    }
}
