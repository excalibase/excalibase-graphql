package io.github.excalibase.cors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The data plane's CORS rule: a refused preflight is the browser's signal to
 * block, while an actual request is always served and only a listed origin is
 * granted {@code Access-Control-Allow-Origin}. Auth is a bearer token, so the
 * Origin header alone gives the server nothing to refuse on.
 */
class BrowserEnforcedCorsProcessorTest {

    private static final String LISTED = "https://app.example.com";
    private static final CorsConfiguration CONFIG = ProjectCorsConfigurationSource.configFor(List.of(LISTED));

    private final BrowserEnforcedCorsProcessor processor = new BrowserEnforcedCorsProcessor();

    private static MockHttpServletRequest actual(String method, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/p1/graphql");
        request.setServerName("api.excalibase.io");
        request.setScheme("https");
        request.setServerPort(443);
        request.addHeader(HttpHeaders.ORIGIN, origin);
        return request;
    }

    private static MockHttpServletRequest preflight(String origin) {
        MockHttpServletRequest request = actual("OPTIONS", origin);
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type");
        return request;
    }

    @Test
    @DisplayName("a listed origin's actual request is served with the origin echoed")
    void listedActualRequestIsGranted() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = processor.processRequest(CONFIG, actual("POST", LISTED), response);

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(LISTED);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)).contains("Content-Range");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
        assertThat(response.getHeaders(HttpHeaders.VARY)).contains(HttpHeaders.ORIGIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "capacitor://localhost", "tauri://localhost", "null", "http://localhost:5173"})
    @DisplayName("an unlisted origin's actual request is served without any grant")
    void unlistedActualRequestIsServedWithoutGrant(String origin) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = processor.processRequest(CONFIG, actual("POST", origin), response);

        assertThat(proceed).as("served, not refused for its Origin").isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        assertThat(response.getHeaders(HttpHeaders.VARY)).contains(HttpHeaders.ORIGIN);
    }

    @Test
    @DisplayName("a listed origin's actual request with a method outside the list is still served")
    void listedOriginUnusualMethodIsServed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = processor.processRequest(CONFIG, actual("HEAD", LISTED), response);

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(LISTED);
    }

    @Test
    @DisplayName("a listed origin's preflight is answered with the grant")
    void listedPreflightIsGranted() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = processor.processRequest(CONFIG, preflight(LISTED), response);

        assertThat(proceed).isTrue();
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(LISTED);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains("POST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "capacitor://localhost", "null"})
    @DisplayName("an unlisted origin's preflight is refused with no CORS headers, so the browser blocks")
    void unlistedPreflightIsRefused(String origin) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = processor.processRequest(CONFIG, preflight(origin), response);

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).isNull();
    }

    @Test
    @DisplayName("a request without Origin is not CORS and passes untouched")
    void noOriginIsUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/p1/graphql");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(processor.processRequest(CONFIG, request, response)).isTrue();
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }
}
