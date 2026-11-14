package io.github.excalibase.security;

import io.github.excalibase.service.VaultCredentialException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KnownProjectFilterTest {

    private static final String PROJECT = "proj-pinned";

    private static MockHttpServletResponse call(KnownProjects projects, String method, String uri, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new KnownProjectFilter(projects).doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("the pinned project reaches the chain on GraphQL, REST and realtime")
    void pinnedProject_reachesChain() throws Exception {
        for (String uri : new String[]{"/" + PROJECT + "/graphql", "/" + PROJECT + "/api/v1/orders",
                "/" + PROJECT + "/api/v1/realtime"}) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletResponse response = call(KnownProjects.pinned(PROJECT), "POST", uri, chain);
            assertThat(response.getStatus()).as(uri).isEqualTo(200);
            verify(chain).doFilter(any(), any());
        }
    }

    @Test
    @DisplayName("any other path project is a 404 on GraphQL, REST and the WebSocket upgrade paths")
    void otherProject_isNotFound() throws Exception {
        for (String uri : new String[]{"/nonexistent/graphql", "/nonexistent/api/v1/orders",
                "/nonexistent/api/v1/realtime"}) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletResponse response = call(KnownProjects.pinned(PROJECT), "GET", uri, chain);
            assertThat(response.getStatus()).as(uri).isEqualTo(404);
            assertThat(response.getContentAsString()).contains("\"code\":\"project_not_found\"");
            verify(chain, never()).doFilter(any(), any());
        }
    }

    @Test
    @DisplayName("a project lookup refused for its rate is a 429 and does not reach the chain")
    void throttledLookup_isTooManyRequests() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        KnownProjects throttled = id -> {
            throw new ProjectLookupThrottledException();
        };

        MockHttpServletResponse response = call(throttled, "POST", "/" + PROJECT + "/graphql", chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("\"code\":\"project_lookup_throttled\"");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("a throttled preflight is answered here, with no lookup and no CORS provider call, and the real request still gets the 429")
    void throttledPreflightIsAnsweredWithoutAnyLookup() throws Exception {
        KnownProjects throttled = id -> {
            throw new ProjectLookupThrottledException();
        };
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/" + PROJECT + "/graphql");
        preflight.setRequestURI("/" + PROJECT + "/graphql");
        preflight.addHeader("Origin", "https://app.example.com");
        preflight.addHeader("Access-Control-Request-Method", "POST");
        preflight.addHeader("Access-Control-Request-Headers", "authorization,content-type");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new KnownProjectFilter(throttled).doFilter(preflight, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://app.example.com");
        assertThat(response.getHeader("Vary")).contains("Origin");
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("POST").contains("DELETE");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).isEqualTo("authorization,content-type");
        assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("0");

        MockHttpServletRequest plainOptions = new MockHttpServletRequest("OPTIONS", "/" + PROJECT + "/graphql");
        plainOptions.setRequestURI("/" + PROJECT + "/graphql");
        MockHttpServletResponse refused = new MockHttpServletResponse();
        new KnownProjectFilter(throttled).doFilter(plainOptions, refused, mock(FilterChain.class));
        assertThat(refused.getStatus()).isEqualTo(429);
    }

    private static final KnownProjects THROTTLED = id -> {
        throw new ProjectLookupThrottledException();
    };

    private static MockHttpServletResponse throttledCall(String method, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/" + PROJECT + "/graphql");
        request.setRequestURI("/" + PROJECT + "/graphql");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        if ("OPTIONS".equals(method)) {
            request.addHeader("Access-Control-Request-Method", "POST");
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        new KnownProjectFilter(THROTTLED).doFilter(request, response, mock(FilterChain.class));
        return response;
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://app.example.com", "http://localhost:3000", "https://a.b.example.com:8443"})
    @DisplayName("a valid origin is echoed on the 429 and on a throttled preflight, never a literal *")
    void validOriginIsEchoed(String origin) throws Exception {
        for (String method : new String[]{"POST", "OPTIONS"}) {
            MockHttpServletResponse response = throttledCall(method, origin);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).as(method).isEqualTo(origin);
            assertThat(response.getHeaders("Vary")).as(method).contains("Origin");
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).as(method).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "ftp://example.com", "https://", "example.com", "https://a.com/path",
            "https://a.com?x=1", "https://user@a.com", "https://a.com#f", "https://a .com", "*",
            "https://a.com\r\nX-Injected: 1", "https://a.com\nSet-Cookie: x=1", ""})
    @DisplayName("a malformed, null or injected origin gets no Access-Control-Allow-Origin")
    void invalidOriginGetsNoAllowOrigin(String origin) throws Exception {
        for (String method : new String[]{"POST", "OPTIONS"}) {
            MockHttpServletResponse response = throttledCall(method, origin);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).as(method + " " + origin).isNull();
            assertThat(response.getHeader("X-Injected")).isNull();
            assertThat(response.getStatus()).isEqualTo("OPTIONS".equals(method) ? 204 : 429);
        }
    }

    @Test
    @DisplayName("a throttled request with no Origin gets no Access-Control-Allow-Origin and never reaches provisioning")
    void missingOriginGetsNoAllowOrigin() throws Exception {
        MockHttpServletResponse response = throttledCall("POST", null);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("with request metrics on, only a project that passed the check is marked known")
    void knownProjectAttribute() throws Exception {
        MockHttpServletRequest known = new MockHttpServletRequest("POST", "/" + PROJECT + "/graphql");
        new KnownProjectFilter(KnownProjects.pinned(PROJECT), true).doFilter(known, new MockHttpServletResponse(),
                mock(FilterChain.class));
        MockHttpServletRequest unknown = new MockHttpServletRequest("POST", "/nonexistent/graphql");
        new KnownProjectFilter(KnownProjects.pinned(PROJECT), true).doFilter(unknown, new MockHttpServletResponse(),
                mock(FilterChain.class));

        assertThat(known.getAttribute(KnownProjectFilter.KNOWN_PROJECT_ATTR)).isEqualTo(PROJECT);
        assertThat(unknown.getAttribute(KnownProjectFilter.KNOWN_PROJECT_ATTR)).isNull();
    }

    @Test
    @DisplayName("with request metrics off, nothing is added to the request")
    void noAttributeWhenMetricsOff() throws Exception {
        MockHttpServletRequest known = new MockHttpServletRequest("POST", "/" + PROJECT + "/graphql");
        new KnownProjectFilter(KnownProjects.pinned(PROJECT)).doFilter(known, new MockHttpServletResponse(),
                mock(FilterChain.class));

        assertThat(known.getAttribute(KnownProjectFilter.KNOWN_PROJECT_ATTR)).isNull();
    }

    @Test
    @DisplayName("routes without a project (health, actuator) are not project-checked")
    void projectlessRoute_reachesChain() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        KnownProjects rejectEverything = projectId -> {
            throw new UnknownProjectException(projectId);
        };

        MockHttpServletResponse response = call(rejectEverything, "GET", "/actuator/health", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("a project lookup that fails (not a 404) is a 503, never a pass-through")
    void lookupFailure_isUnavailable() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        KnownProjects unreachable = projectId -> {
            throw new VaultCredentialException("provisioning unreachable");
        };

        MockHttpServletResponse response = call(unreachable, "POST", "/proj-x/graphql", chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"code\":\"project_unavailable\"")
                .doesNotContain("provisioning unreachable");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("a project without a database is a 409 naming that, never a pass-through or a fallback")
    void projectWithoutDatabase_isConflict() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        KnownProjects noDatabase = projectId -> {
            throw new ProjectWithoutDatabaseException(projectId);
        };

        MockHttpServletResponse response = call(noDatabase, "POST", "/proj-x/graphql", chain);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("\"code\":\"project_has_no_database\"");
        verify(chain, never()).doFilter(any(), any());
    }
}
