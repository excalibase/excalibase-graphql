package io.github.excalibase.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excalibase.config.ws.GraphQLWebSocketHandler;
import io.github.excalibase.config.ws.RealtimeWebSocketHandler;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.security.JwtAuthFilter;
import io.micronaut.context.ApplicationContext;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Whatever is written in {@code app.security.jwt-enabled}, start-up, the authentication filter and the engine
 * (schema manager and both WebSocket handlers) agree on it, or the engine does not start. A spelling that left the
 * filter on and the engine serving every caller as the service role would be open.
 */
class JwtEnabledAgreementTest {

    private static final String HMAC = "test-hmac-secret-0123456789abcdef0123456789";

    private static Map<String, Object> engine(String value, boolean insecureDevMode) {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake",
                "spring.datasource.password", "fake",
                "app.project-id", "test-proj",
                "app.nats.enabled", "false",
                "micronaut.server.port", "-1",
                "endpoints.all.port", "-1",
                "app.security.auth.hmac-secret", HMAC,
                "app.security.jwt-enabled", value));
        if (insecureDevMode) {
            properties.put("app.security.insecure-dev-mode", "true");
        }
        return properties;
    }

    private static boolean flag(Object bean, String field) throws Exception {
        Field declared = bean.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        return declared.getBoolean(bean);
    }

    static Stream<Arguments> accepted() {
        return Stream.of(Arguments.of("true", true), Arguments.of("TRUE", true), Arguments.of("True", true),
                Arguments.of("false", false), Arguments.of("FALSE", false), Arguments.of("False", false));
    }

    @ParameterizedTest(name = "jwt-enabled=\"{0}\": filter, schema manager and both WebSocket handlers all read {1}")
    @MethodSource("accepted")
    @DisplayName("a spelling that is accepted is read the same way by the filter and the engine")
    void acceptedValuesAgree(String value, boolean enabled) throws Exception {
        try (ApplicationContext context = ApplicationContext.run(engine(value, true))) {
            assertThat(context.containsBean(JwtAuthFilter.class)).isEqualTo(enabled);
            assertThat(flag(context.getBean(GraphqlSchemaManager.class), "jwtEnabled")).isEqualTo(enabled);
            assertThat(flag(context.getBean(GraphQLWebSocketHandler.class), "jwtEnabled")).isEqualTo(enabled);
            assertThat(flag(context.getBean(RealtimeWebSocketHandler.class), "jwtEnabled")).isEqualTo(enabled);
            assertThat(context.containsBean(InsecureDevModeGuard.class)).isEqualTo(!enabled);
        }
    }

    @ParameterizedTest(name = "jwt-enabled={0} without the dev flag does not start")
    @ValueSource(strings = {"FALSE", "False", "false"})
    @DisplayName("authentication off in any case needs the insecure dev flag")
    void offNeedsTheFlag(String value) {
        assertThatThrownBy(() -> ApplicationContext.run(engine(value, false)).close())
                .hasStackTraceContaining("app.security.insecure-dev-mode");
    }

    @ParameterizedTest(name = "jwt-enabled=\"{0}\" does not start")
    @ValueSource(strings = {"", " ", " true ", "True\n", " false ", "false\n", "\ttrue", "no", "0", "off", "yes", "1", "on"})
    @DisplayName("blank, padded and every other spelling stops start-up, with or without the dev flag")
    void everythingElseIsRefused(String value) {
        assertThatThrownBy(() -> ApplicationContext.run(engine(value, true)).close())
                .hasStackTraceContaining("app.security.jwt-enabled must be true or false");
        assertThatThrownBy(() -> ApplicationContext.run(engine(value, false)).close())
                .hasStackTraceContaining("app.security.jwt-enabled must be true or false");
    }
}
