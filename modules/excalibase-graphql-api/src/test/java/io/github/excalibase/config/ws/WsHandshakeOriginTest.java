package io.github.excalibase.config.ws;

import io.github.excalibase.cors.CorsRules;
import io.github.excalibase.cors.ProjectCorsConfigurationSource;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browsers do not apply CORS to WebSockets, so the upgrade checks Origin
 * itself: a web page (http/https) must be on the project's allowlist; no
 * Origin (server clients) and native-app schemes (Capacitor, Tauri, Electron,
 * file pages sending {@code null}) are not web pages and pass.
 */
class WsHandshakeOriginTest {

    private static final String LISTED = "https://app.example.com";

    private final WsHandshakeFilter filter = filterFor(List.of(LISTED));

    private static WsHandshakeFilter filterFor(List<String> origins) {
        CorsRules platformDefault = ProjectCorsConfigurationSource.configFor(List.of());
        ProjectCorsConfigurationSource source = new ProjectCorsConfigurationSource(
                request -> origins, platformDefault);
        return new WsHandshakeFilter(source, null);
    }

    private static MutableHttpRequest<?> upgrade(String origin) {
        MutableHttpRequest<?> request = HttpRequest.GET("/p1/api/v1/realtime")
                .header("Host", "api.excalibase.io")
                .header("Upgrade", "websocket");
        return origin == null ? request : request.header("Origin", origin);
    }

    private HttpResponse<?> handshake(String origin) {
        return filter.filter(upgrade(origin));
    }

    @Test
    @DisplayName("a listed web origin may open the socket")
    void listedOriginPasses() {
        assertThat(handshake(LISTED)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "http://app.example.com", "http://localhost:5173",
            "HTTPS://EVIL.EXAMPLE"})
    @DisplayName("an unlisted web origin is refused with 403 at upgrade")
    void unlistedWebOriginIsRefused(String origin) {
        assertThat(handshake(origin).code()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"capacitor://localhost", "ionic://localhost", "tauri://localhost", "app://bundle",
            "file://", "null"})
    @DisplayName("a native-app origin is not a web page and passes")
    void nativeOriginPasses(String origin) {
        assertThat(handshake(origin)).isNull();
    }

    @Test
    @DisplayName("an upgrade without Origin (server-side client) passes")
    void noOriginPasses() {
        assertThat(handshake(null)).isNull();
    }

    @Test
    @DisplayName("a same-origin page passes without being listed")
    void sameOriginPasses() {
        assertThat(handshake("http://api.excalibase.io")).isNull();
    }

    @Test
    @DisplayName("a project whose allowlist is '*' admits any web origin")
    void wildcardAdmitsAnyWebOrigin() {
        assertThat(filterFor(List.of("*")).filter(upgrade("https://evil.example"))).isNull();
    }

    @Test
    @DisplayName("an origin with no scheme separator is checked as a web origin and refused")
    void originWithoutSchemeIsRefused() {
        assertThat(handshake("app.example.com").code()).isEqualTo(403);
    }

    @Test
    @DisplayName("a malformed web-looking origin is refused")
    void malformedOriginIsRefused() {
        assertThat(handshake("https://").code()).isEqualTo(403);
    }

    @Test
    @DisplayName("a request that is not an upgrade is not this filter's business")
    void plainRequestIsIgnored() {
        assertThat(filter.filter(HttpRequest.GET("/p1/graphql").header("Origin", "https://evil.example"))).isNull();
    }
}
