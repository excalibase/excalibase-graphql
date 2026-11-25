package io.github.excalibase.admin;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Auth page needs the console: a flag that would do nothing refuses start, and a misspelt one too. */
class AuthSubFlagStartupTest {

    private static final String FALSE = "false";

    private static Map<String, Object> engine(String console, String authPage) {
        return new HashMap<>(Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake", "spring.datasource.password", "fake",
                "app.project-id", "demo", "app.nats.enabled", FALSE,
                "micronaut.server.port", "-1", "endpoints.all.port", "-1",
                "excalibase.console.enabled", console, "console.auth-page.enabled", authPage));
    }

    @Test
    @DisplayName("the Auth page on with the console off refuses start")
    void withoutTheConsole() {
        Map<String, Object> properties = engine(FALSE, "true");

        assertThatThrownBy(() -> ApplicationContext.run(properties).close())
                .hasStackTraceContaining("CONSOLE_AUTH_PAGE_ENABLED")
                .hasStackTraceContaining("EXCALIBASE_CONSOLE_ENABLED");
    }

    @Test
    @DisplayName("a value that is not true or false refuses start")
    void misspelt() {
        Map<String, Object> properties = engine(FALSE, "yes");

        assertThatThrownBy(() -> ApplicationContext.run(properties).close())
                .hasStackTraceContaining("CONSOLE_AUTH_PAGE_ENABLED must be true or false");
    }
}
