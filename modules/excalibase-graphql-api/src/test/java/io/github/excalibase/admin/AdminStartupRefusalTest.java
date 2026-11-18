package io.github.excalibase.admin;

import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.FreePort;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A real context start, so the property names behind the checks are pinned too. */
class AdminStartupRefusalTest {

    /** Multi-tenant mode serves no default database; the other modes need theirs, or an earlier bean stops the start. */
    private static Map<String, Object> consoleOn(String key, String value, boolean defaultDatabase) {
        Map<String, String> properties = AdminTestEngine.consoleOn(
                AdminTestEngine.documentFile(AdminTestEngine.DOCUMENT), -1, FreePort.find());
        properties.putAll(EngineProperties.isolated());
        properties.keySet().removeIf(name -> name.equals("metadata.source") || name.startsWith("metadata.file.")
                || (!defaultDatabase && name.startsWith("spring.datasource.")));
        if (key.equals("excalibase.console.enabled")) {
            properties.put("metadata.source", "file");
            properties.put("metadata.file.path", AdminTestEngine.documentFile(AdminTestEngine.DOCUMENT).toString());
        }
        properties.put(key, value);
        return Map.<String, Object>copyOf(properties);
    }

    private static void start(Map<String, Object> properties) {
        try (ApplicationContext context = ApplicationContext.run(properties)) {
            context.getBeanDefinitions(Object.class);
        }
    }

    @Test
    void aPolicyUrlWithTheConsoleOnRefusesToStart() {
        Map<String, Object> properties = consoleOn("app.security.rls.policy-url", "http://localhost:59998/api/provision", true);

        assertThatThrownBy(() -> start(properties)).hasStackTraceContaining("EXCALIBASE_CONSOLE_ENABLED=true is refused");
    }

    @Test
    void aProvisioningUrlWithTheConsoleOnRefusesToStart() {
        Map<String, Object> properties = consoleOn("app.security.multi-tenant.provisioning-url", "http://localhost:59998/api", false);

        assertThatThrownBy(() -> start(properties)).hasStackTraceContaining("EXCALIBASE_CONSOLE_ENABLED=true is refused");
    }

    @Test
    void anUnknownSpellingOfTheFlagRefusesToStart() {
        Map<String, Object> properties = consoleOn("excalibase.console.enabled", "yes", true);

        assertThatThrownBy(() -> start(properties)).hasStackTraceContaining("EXCALIBASE_CONSOLE_ENABLED must be true or false");
    }
}
