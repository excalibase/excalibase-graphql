package io.github.excalibase.security;

import io.github.excalibase.service.VaultCredentialException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class KnownProjectFilterTest {

    private static final String PROJECT = "proj-pinned";

    /** The refusal the filter answers, or null when the request goes on to the handler. */
    private static HttpResponse<?> call(KnownProjects projects, String uri) {
        return new KnownProjectFilter(projects, false).filter(HttpRequest.POST(uri, ""));
    }

    private static String body(HttpResponse<?> response) {
        return String.valueOf(response.body());
    }

    @Test
    @DisplayName("the pinned project reaches the handler on GraphQL, REST and realtime")
    void pinnedProject_reachesChain() {
        for (String uri : new String[]{"/" + PROJECT + "/graphql", "/" + PROJECT + "/api/v1/orders",
                "/" + PROJECT + "/api/v1/realtime"}) {
            assertThat(call(KnownProjects.pinned(PROJECT), uri)).as(uri).isNull();
        }
    }

    @Test
    @DisplayName("any other path project is a 404 on GraphQL, REST and the WebSocket upgrade paths")
    void otherProject_isNotFound() {
        for (String uri : new String[]{"/nonexistent/graphql", "/nonexistent/api/v1/orders",
                "/nonexistent/api/v1/realtime"}) {
            HttpResponse<?> response = call(KnownProjects.pinned(PROJECT), uri);
            assertThat(response.code()).as(uri).isEqualTo(404);
            assertThat(body(response)).contains("\"code\":\"project_not_found\"");
        }
    }

    private static final KnownProjects THROTTLED = id -> {
        throw new ProjectLookupThrottledException();
    };

    @Test
    @DisplayName("a throttled lookup is a 429 a browser can read: the asking origin, and Vary")
    void throttledLookupIsReadableByABrowser() {
        MutableHttpRequest<?> request = HttpRequest.POST("/" + PROJECT + "/graphql", "")
                .header("Origin", "https://app.example.com");

        HttpResponse<?> response = new KnownProjectFilter(THROTTLED, false).filter(request);

        assertThat(response.code()).isEqualTo(429);
        assertThat(response.getHeaders().get("Access-Control-Allow-Origin")).isEqualTo("https://app.example.com");
        assertThat(response.getHeaders().getAll("Vary")).contains("Origin");
        assertThat(body(response)).contains("\"code\":\"project_lookup_throttled\"");
    }

    @Test
    @DisplayName("a throttled preflight is answered here, without ever reaching the CORS lookup, and the real request still gets the 429")
    void throttledPreflightIsAnsweredWithoutAnyLookup() {
        MutableHttpRequest<?> preflight = HttpRequest.OPTIONS("/" + PROJECT + "/graphql")
                .header("Origin", "https://app.example.com")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type");

        HttpResponse<?> response = new KnownProjectFilter(THROTTLED, false).filter(preflight);

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(204);
        assertThat(response.getHeaders().get("Access-Control-Allow-Origin")).isEqualTo("https://app.example.com");
        assertThat(response.getHeaders().getAll("Vary")).contains("Origin");
        assertThat(response.getHeaders().get("Access-Control-Allow-Methods")).contains("POST").contains("DELETE");
        assertThat(response.getHeaders().get("Access-Control-Allow-Headers")).isEqualTo("authorization,content-type");
        assertThat(response.getHeaders().get("Access-Control-Max-Age")).isEqualTo("0");

        HttpResponse<?> plain = new KnownProjectFilter(THROTTLED, false).filter(HttpRequest.OPTIONS("/" + PROJECT + "/graphql"));
        assertThat(plain.code()).isEqualTo(429);
    }

    private static HttpResponse<?> throttled(io.micronaut.http.HttpMethod method, String origin) {
        MutableHttpRequest<?> request = HttpRequest.create(method, "/" + PROJECT + "/graphql");
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (method == io.micronaut.http.HttpMethod.OPTIONS) {
            request.header("Access-Control-Request-Method", "POST");
        }
        return new KnownProjectFilter(THROTTLED, false).filter(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://app.example.com", "http://localhost:3000", "https://a.b.example.com:8443"})
    @DisplayName("a valid origin is echoed on the 429 and on a throttled preflight, never a literal *")
    void validOriginIsEchoed(String origin) {
        for (io.micronaut.http.HttpMethod method : new io.micronaut.http.HttpMethod[]{
                io.micronaut.http.HttpMethod.POST, io.micronaut.http.HttpMethod.OPTIONS}) {
            HttpResponse<?> response = throttled(method, origin);
            assertThat(response.getHeaders().get("Access-Control-Allow-Origin")).as(method.name()).isEqualTo(origin);
            assertThat(response.getHeaders().getAll("Vary")).as(method.name()).contains("Origin");
            assertThat(response.getHeaders().get("Access-Control-Allow-Credentials")).as(method.name()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "ftp://example.com", "https://", "example.com", "https://a.com/path",
            "https://a.com?x=1", "https://user@a.com", "https://a.com#f", "https://a .com", "*", ""})
    @DisplayName("a malformed, null or injected origin gets no Access-Control-Allow-Origin")
    void invalidOriginGetsNoAllowOrigin(String origin) {
        for (io.micronaut.http.HttpMethod method : new io.micronaut.http.HttpMethod[]{
                io.micronaut.http.HttpMethod.POST, io.micronaut.http.HttpMethod.OPTIONS}) {
            HttpResponse<?> response = throttled(method, origin);
            assertThat(response.getHeaders().get("Access-Control-Allow-Origin")).as(method.name() + " " + origin).isNull();
            assertThat(response.getHeaders().get("X-Injected")).isNull();
            assertThat(response.code()).isEqualTo(method == io.micronaut.http.HttpMethod.OPTIONS ? 204 : 429);
        }
    }

    @Test
    @DisplayName("a CR or LF in an Origin never reaches a response: the server refuses to build the request")
    void crlfOriginCannotBeBuilt() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> throttled(io.micronaut.http.HttpMethod.POST, "https://a.com\r\nX-Injected: 1"))).isNotNull();
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> throttled(io.micronaut.http.HttpMethod.POST, "https://a.com\nSet-Cookie: x=1"))).isNotNull();
    }

    @Test
    @DisplayName("a throttled request with no Origin gets no Access-Control-Allow-Origin")
    void missingOriginGetsNoAllowOrigin() {
        HttpResponse<?> response = throttled(io.micronaut.http.HttpMethod.POST, null);
        assertThat(response.getHeaders().get("Access-Control-Allow-Origin")).isNull();
        assertThat(response.code()).isEqualTo(429);
    }

    @Test
    @DisplayName("with request metrics on, only a project that passed the check is marked known")
    void knownProjectAttribute() {
        HttpRequest<?> known = HttpRequest.POST("/" + PROJECT + "/graphql", "");
        new KnownProjectFilter(KnownProjects.pinned(PROJECT), true).filter(known);
        HttpRequest<?> unknown = HttpRequest.POST("/nonexistent/graphql", "");
        new KnownProjectFilter(KnownProjects.pinned(PROJECT), true).filter(unknown);

        assertThat(known.getAttribute(KnownProjectFilter.KNOWN_PROJECT_ATTR).orElse(null)).isEqualTo(PROJECT);
        assertThat(unknown.getAttribute(KnownProjectFilter.KNOWN_PROJECT_ATTR)).isEmpty();
    }

    @Test
    @DisplayName("with request metrics off, nothing is added to the request")
    void noAttributeWhenMetricsOff() {
        HttpRequest<?> known = HttpRequest.POST("/" + PROJECT + "/graphql", "");
        new KnownProjectFilter(KnownProjects.pinned(PROJECT), false).filter(known);

        assertThat(known.getAttribute(KnownProjectFilter.KNOWN_PROJECT_ATTR)).isEmpty();
    }

    @Test
    @DisplayName("routes without a project (health, actuator) are not project-checked")
    void projectlessRoute_reachesChain() {
        KnownProjects rejectEverything = projectId -> {
            throw new UnknownProjectException(projectId);
        };

        assertThat(call(rejectEverything, "/actuator/health")).isNull();
    }

    @Test
    @DisplayName("a project lookup that fails (not a 404) is a 503, never a pass-through")
    void lookupFailure_isUnavailable() {
        KnownProjects unreachable = projectId -> {
            throw new VaultCredentialException("provisioning unreachable");
        };

        HttpResponse<?> response = call(unreachable, "/proj-x/graphql");

        assertThat(response.code()).isEqualTo(503);
        assertThat(body(response)).contains("\"code\":\"project_unavailable\"")
                .doesNotContain("provisioning unreachable");
    }

    @Test
    @DisplayName("a project without a database is a 409 naming that, never a pass-through or a fallback")
    void projectWithoutDatabase_isConflict() {
        KnownProjects noDatabase = projectId -> {
            throw new ProjectWithoutDatabaseException(projectId);
        };

        HttpResponse<?> response = call(noDatabase, "/proj-x/graphql");

        assertThat(response.code()).isEqualTo(409);
        assertThat(body(response)).contains("\"code\":\"project_has_no_database\"");
    }
}
