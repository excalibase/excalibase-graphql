package io.github.excalibase.config.ws;

import io.github.excalibase.cors.ProjectCorsConfigurationSource;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The handshake filter itself refuses an upgrade on a WebSocket route that names no project; it does not rely on a
 * later filter to do it, since an upgrade that passes here has no origin check, no authentication and no caps.
 */
class WsHandshakeNoProjectTest {

    private final WsHandshakeFilter filter = new WsHandshakeFilter(new ProjectCorsConfigurationSource(
            request -> List.of(), ProjectCorsConfigurationSource.configFor(List.of())), null);

    private HttpResponse<?> upgrade(String path) {
        return filter.filter(HttpRequest.GET(path).header("Host", "api.excalibase.io").header("Upgrade", "websocket"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/p/graphql/", "/p/api/v1/realtime/", "/p%2Fq/graphql", "/p%2Fq/api/v1/realtime",
            "/a/b/graphql"})
    @DisplayName("an upgrade on a WebSocket route with no parsed project is a 404")
    void noProjectIsNotFound(String path) {
        HttpResponse<?> answer = upgrade(path);

        assertThat(answer).isNotNull();
        assertThat(answer.code()).isEqualTo(404);
    }

    @Test
    @DisplayName("a path that is no WebSocket route is left to the router")
    void otherPathsPass() {
        assertThat(upgrade("/actuator/health")).isNull();
    }

    @Test
    @DisplayName("a project route passes the filter")
    void projectRoutePasses() {
        assertThat(upgrade("/p1/graphql")).isNull();
    }
}
