package io.github.excalibase.observability.requests;

import io.github.excalibase.security.KnownProjectFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestMetricsFilterTest {

    private static final int MAIN_PORT = 10000;
    private static final int METRICS_PORT = 9464;
    private static final String PROJECT = "proj-a";

    private final RequestMetrics metrics = new RequestMetrics(10);
    private final RequestMetricsFilter filter = new RequestMetricsFilter(metrics, METRICS_PORT);

    @Test
    @DisplayName("the internal port answers GET /metrics with the scrape")
    void internalPortServesScrape() throws Exception {
        metrics.recordRequest(PROJECT, RequestFamily.GRAPHQL, RequestOutcome.OK, Duration.ZERO);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", METRICS_PORT, "/metrics"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/plain").contains("version=0.0.4");
        assertThat(response.getContentAsString()).contains("excalibase_requests_seconds_count{");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("anything else on the internal port is 404 and never reaches the application")
    void internalPortRefusesOtherPaths() throws Exception {
        for (String[] methodAndPath : new String[][]{{"GET", "/proj-a/graphql"}, {"POST", "/metrics"},
                {"GET", "/actuator/health"}, {"GET", "/metrics/x"}}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request(methodAndPath[0], METRICS_PORT, methodAndPath[1]), response, chain);

            assertThat(response.getStatus()).as(methodAndPath[1]).isEqualTo(404);
            assertThat(chain.getRequest()).as(methodAndPath[1]).isNull();
        }
    }

    @Test
    @DisplayName("the main port does not serve the scrape at /metrics")
    void mainPortPassesMetricsPathThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", MAIN_PORT, "/metrics"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getContentAsString()).doesNotContain("excalibase_");
    }

    @Test
    @DisplayName("a request for a known project is timed with its family and outcome")
    void recordsKnownProject() throws Exception {
        filter.doFilter(request("POST", MAIN_PORT, "/proj-a/graphql"), new MockHttpServletResponse(),
                chainAnswering(200, true));
        filter.doFilter(request("GET", MAIN_PORT, "/proj-a/api/v1/orders"), new MockHttpServletResponse(),
                chainAnswering(404, true));

        assertThat(metrics.scrape())
                .contains("excalibase_requests_seconds_count{family=\"graphql\",outcome=\"ok\",project=\"proj-a\"} 1")
                .contains("excalibase_requests_seconds_count{family=\"rest\",outcome=\"client_error\",project=\"proj-a\"} 1");
    }

    @Test
    @DisplayName("a GraphQL answer of 200 carrying errors is a client error")
    void graphqlErrorsInOk() throws Exception {
        FilterChain chain = (request, response) -> {
            markKnown(request);
            request.setAttribute(RequestMetrics.GRAPHQL_ERRORS_ATTR, Boolean.TRUE);
            ((HttpServletResponse) response).setStatus(200);
        };

        filter.doFilter(request("POST", MAIN_PORT, "/proj-a/graphql"), new MockHttpServletResponse(), chain);

        assertThat(metrics.scrape()).contains(
                "excalibase_requests_seconds_count{family=\"graphql\",outcome=\"client_error\",project=\"proj-a\"} 1");
    }

    @Test
    @DisplayName("a request the known-project check did not pass is not recorded")
    void unknownProjectNotRecorded() throws Exception {
        filter.doFilter(request("POST", MAIN_PORT, "/random-xyz/graphql"), new MockHttpServletResponse(),
                chainAnswering(404, false));

        assertThat(metrics.scrape()).doesNotContain("random-xyz").doesNotContain("excalibase_requests_seconds_count");
    }

    @Test
    @DisplayName("WebSocket upgrades are not counted as HTTP requests")
    void upgradesSkipped() throws Exception {
        MockHttpServletRequest upgrade = request("GET", MAIN_PORT, "/proj-a/graphql");
        upgrade.addHeader("Upgrade", "websocket");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(upgrade, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(metrics.scrape()).doesNotContain("excalibase_requests_seconds_count");
    }

    @Test
    @DisplayName("a request whose handling throws is a server error")
    void thrownIsServerError() {
        FilterChain chain = (request, response) -> {
            markKnown(request);
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request("POST", MAIN_PORT, "/proj-a/graphql"),
                new MockHttpServletResponse(), chain)).isInstanceOf(ServletException.class);
        assertThat(metrics.scrape()).contains(
                "excalibase_requests_seconds_count{family=\"graphql\",outcome=\"server_error\",project=\"proj-a\"} 1");
    }

    private static FilterChain chainAnswering(int status, boolean known) {
        return (request, response) -> {
            if (known) {
                markKnown(request);
            }
            ((HttpServletResponse) response).setStatus(status);
        };
    }

    private static void markKnown(ServletRequest request) {
        request.setAttribute(KnownProjectFilter.KNOWN_PROJECT_ATTR, PROJECT);
    }

    private static MockHttpServletRequest request(String method, int localPort, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setLocalPort(localPort);
        return request;
    }
}
