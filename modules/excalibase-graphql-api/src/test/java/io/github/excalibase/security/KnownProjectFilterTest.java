package io.github.excalibase.security;

import io.github.excalibase.service.VaultCredentialException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
