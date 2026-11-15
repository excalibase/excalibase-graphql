package io.github.excalibase.cors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A wildcard allowlist never answers a literal {@code *}: it echoes a valid origin, and refuses an opaque one. */
class CorsWildcardTest {

    private final CorsRules rules = ProjectCorsConfigurationSource.configFor(List.of("*"));

    @ParameterizedTest
    @ValueSource(strings = {"https://app.example.com", "http://localhost:5173", "capacitor://localhost",
            "https://[2001:db8::1]:8443"})
    @DisplayName("a valid origin is echoed")
    void validOriginIsEchoed(String origin) {
        assertThat(rules.checkOrigin(origin)).isEqualTo(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "NULL", "file://", "https:", "https://a.com/path", "https://user@a.com",
            "https://a.com?x=1", "https://a .com", "*", "app", "https://"})
    @DisplayName("an opaque or malformed origin is refused, not granted *")
    void opaqueOriginIsRefused(String origin) {
        assertThat(rules.checkOrigin(origin)).isNull();
    }

    @Test
    @DisplayName("no origin gets nothing")
    void noOrigin() {
        assertThat(rules.checkOrigin(null)).isNull();
        assertThat(rules.checkOrigin(" ")).isNull();
    }

    @Test
    @DisplayName("a wildcard listed with other origins still echoes and never sends *")
    void wildcardAmongOthers() {
        CorsRules mixed = ProjectCorsConfigurationSource.configFor(List.of("https://a.example", "*"));

        assertThat(mixed.checkOrigin("https://b.example")).isEqualTo("https://b.example");
        assertThat(mixed.checkOrigin("null")).isNull();
    }

    @Test
    @DisplayName("an explicitly listed null origin is still honoured as listed")
    void listedNullIsHonoured() {
        assertThat(ProjectCorsConfigurationSource.configFor(List.of("null")).checkOrigin("null")).isEqualTo("null");
    }
}
