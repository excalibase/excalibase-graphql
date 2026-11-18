package io.github.excalibase.admin;

import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.FreePort;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The engine refuses to start with the console on and anything wrong about it. */
class AdminStartupRefusalTest {

    private static Map<String, String> consoleOn() {
        Map<String, String> properties = AdminTestEngine.consoleOn(
                AdminTestEngine.documentFile(AdminTestEngine.DOCUMENT), -1, FreePort.find());
        properties.putAll(EngineProperties.isolated());
        return properties;
    }

    private static String stackTrace(Throwable thrown) {
        StringWriter text = new StringWriter();
        thrown.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static void startWith(Map<String, String> properties) {
        try (ApplicationContext context = ApplicationContext.run(Map.<String, Object>copyOf(properties))) {
            context.getBeanDefinitions(Object.class);
        }
    }

    @Test
    void aMissingSecretRefusesToStart() {
        Map<String, String> properties = consoleOn();
        properties.remove("excalibase.admin.secret");

        assertThatThrownBy(() -> startWith(properties)).hasStackTraceContaining("EXCALIBASE_ADMIN_SECRET");
    }

    @Test
    void hostedModeRefusesTheConsole() {
        Map<String, String> properties = consoleOn();
        properties.put("app.security.multi-tenant.provisioning-url", "http://localhost:59998/api");
        properties.keySet().removeIf(key -> key.startsWith("spring.datasource.") || key.equals("metadata.source")
                || key.startsWith("metadata.file."));

        assertThatThrownBy(() -> startWith(properties))
                .hasStackTraceContaining("EXCALIBASE_CONSOLE_ENABLED")
                .satisfies(thrown -> assertThat(stackTrace(thrown)).doesNotContain(SECRET));
    }
}
