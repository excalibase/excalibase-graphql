package io.github.excalibase.config.ws;

import io.github.excalibase.cors.ProjectCorsConfigurationSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Browsers do not apply CORS to WebSockets, so the upgrade checks Origin
 * itself: a web page (http/https) must be on the project's allowlist; no
 * Origin (server clients) and native-app schemes (Capacitor, Tauri, Electron,
 * file pages sending {@code null}) are not web pages and pass.
 */
class WebSocketOriginInterceptorTest {

    private static final String LISTED = "https://app.example.com";
    private static final CorsConfigurationSource SOURCE =
            request -> ProjectCorsConfigurationSource.configFor(List.of(LISTED));
    private static final WebSocketHandler HANDLER = new TextWebSocketHandler();

    private final WebSocketOriginInterceptor interceptor = new WebSocketOriginInterceptor(SOURCE);

    private static MockHttpServletRequest upgrade(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/p1/api/v1/realtime");
        request.setScheme("https");
        request.setServerName("api.excalibase.io");
        request.setServerPort(443);
        request.addHeader(HttpHeaders.UPGRADE, "websocket");
        if (origin != null) {
            request.addHeader(HttpHeaders.ORIGIN, origin);
        }
        return request;
    }

    private boolean handshake(String origin, MockHttpServletResponse response) {
        return interceptor.beforeHandshake(new ServletServerHttpRequest(upgrade(origin)),
                new ServletServerHttpResponse(response), HANDLER, new HashMap<>());
    }

    @Test
    @DisplayName("a listed web origin may open the socket")
    void listedOriginPasses() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(handshake(LISTED, response)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "http://app.example.com", "http://localhost:5173",
            "HTTPS://EVIL.EXAMPLE"})
    @DisplayName("an unlisted web origin is refused with 403 at upgrade")
    void unlistedWebOriginIsRefused(String origin) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(handshake(origin, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"capacitor://localhost", "ionic://localhost", "tauri://localhost", "app://bundle",
            "file://", "null"})
    @DisplayName("a native-app origin is not a web page and passes")
    void nativeOriginPasses(String origin) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(handshake(origin, response)).isTrue();
    }

    @Test
    @DisplayName("an upgrade without Origin (server-side client) passes")
    void noOriginPasses() {
        assertThat(handshake(null, new MockHttpServletResponse())).isTrue();
    }

    @Test
    @DisplayName("a same-origin page passes without being listed")
    void sameOriginPasses() {
        assertThat(handshake("https://api.excalibase.io", new MockHttpServletResponse())).isTrue();
    }

    @Test
    @DisplayName("a project whose allowlist is '*' admits any web origin")
    void wildcardAdmitsAnyWebOrigin() {
        WebSocketOriginInterceptor open = new WebSocketOriginInterceptor(
                request -> ProjectCorsConfigurationSource.configFor(List.of("*")));

        assertThat(open.beforeHandshake(new ServletServerHttpRequest(upgrade("https://evil.example")),
                new ServletServerHttpResponse(new MockHttpServletResponse()), HANDLER, new HashMap<>())).isTrue();
    }

    @Test
    @DisplayName("a web origin on a non-servlet request cannot be resolved to a project and is refused")
    void nonServletRequestIsRefused() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(LISTED);
        when(request.getHeaders()).thenReturn(headers);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.beforeHandshake(request, new ServletServerHttpResponse(response), HANDLER, new HashMap<>()))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("an origin with no scheme separator is checked as a web origin and refused")
    void originWithoutSchemeIsRefused() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(handshake("app.example.com", response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("the after-handshake hook does nothing")
    void afterHandshakeIsANoOp() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.afterHandshake(new ServletServerHttpRequest(upgrade(LISTED)),
                new ServletServerHttpResponse(response), HANDLER, null);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a malformed web-looking origin is refused")
    void malformedOriginIsRefused() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(handshake("https://", response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }
}
