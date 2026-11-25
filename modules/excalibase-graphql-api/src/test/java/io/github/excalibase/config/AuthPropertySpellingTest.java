package io.github.excalibase.config;

import io.github.excalibase.metadata.AuthSettingsReader;
import io.github.excalibase.metadata.AuthSpellings;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The allow-list may accept only the spellings Micronaut really reads under the engine's names, and refuse the rest:
 * a spelling that is accepted but never read would start cleanly and silently leave a default in place.
 */
class AuthPropertySpellingTest {

    private static Environment environment(String key) {
        return ApplicationContext.builder().properties(Map.of("app.auth." + key, "value")).build().getEnvironment()
                .start();
    }

    private static boolean readUnderAnEngineName(String spelling) {
        Environment environment = environment(spelling);
        return AuthSettingsReader.KEYS.stream()
                .anyMatch(key -> "value".equals(environment.getProperty("app.auth." + key, String.class).orElse(null)));
    }

    @Test
    void everyAcceptedSpelling_isReadByMicronaut() {
        for (String spelling : AuthSpellings.READ) {
            assertThat(readUnderAnEngineName(spelling)).as(spelling).isTrue();
        }
    }

    @Test
    void everyRefusedSpelling_isNeverReadByMicronaut() {
        for (String spelling : AuthSpellings.NEVER_READ) {
            assertThat(readUnderAnEngineName(spelling)).as(spelling).isFalse();
        }
    }

    @Test
    void theNamesTheEngineDocumentsAreTheCanonicalKeys() {
        List<String> expected = List.of("issuer", "jwks-url", "secret", "secret-file", "algorithms", "audience",
                "clock-skew", "claims.role", "claims.default-role", "claims.user-id", "claims.extra");

        assertThat(expected).containsExactlyInAnyOrderElementsOf(AuthSettingsReader.KEYS);
    }
}
