package io.github.excalibase.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.SecurityConstants;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerMapping;

class HttpRequestTimerFilterTest {

    private static final String PATTERN = "/{projectId}/api/v1/{table}";

    private SimpleMeterRegistry registry;
    private HttpRequestTimerFilter filter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        filter = new HttpRequestTimerFilter(registry, true);
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    private static FilterChain respondWith(int status, String pattern) {
        return (req, res) -> {
            if (pattern != null) {
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
            }
            ((MockHttpServletResponse) res).setStatus(status);
        };
    }

    private Timer timer() {
        return registry.get("http.server.requests").timer();
    }

    @Test
    void recordsTheTagsTheObservationConventionProduced() throws Exception {
        MockHttpServletRequest request = request("/acme/api/v1/customers");
        request.setAttribute(SecurityConstants.JWT_CLAIMS_ATTR, new JwtClaims(
                "u1", "proj-1", "acme-org", "Shop", "Acme Inc", "user", "a@b.c", null, 0));

        filter.doFilter(request, new MockHttpServletResponse(), respondWith(200, PATTERN));

        Timer timer = timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTags()).extracting(tag -> tag.getKey() + "=" + tag.getValue())
                .containsExactlyInAnyOrder(
                        "method=GET", "status=200", "uri=" + PATTERN, "outcome=SUCCESS",
                        "exception=none", "error=none",
                        "tenant.id=proj-1", "org.slug=acme-org", "project.name=Shop", "org.name=Acme Inc");
    }

    @Test
    void withoutClaimsTheTenantTagsAreNone() throws Exception {
        filter.doFilter(request("/health"), new MockHttpServletResponse(), respondWith(200, "/health"));

        assertThat(timer().getId().getTag("tenant.id")).isEqualTo("none");
        assertThat(timer().getId().getTag("org.slug")).isEqualTo("none");
        assertThat(timer().getId().getTag("project.name")).isEqualTo("none");
        assertThat(timer().getId().getTag("org.name")).isEqualTo("none");
    }

    @Test
    void uriIsThePatternSoDifferentPathsShareOneTimer() throws Exception {
        filter.doFilter(request("/acme/api/v1/customers"), new MockHttpServletResponse(), respondWith(200, PATTERN));
        filter.doFilter(request("/acme/api/v1/orders"), new MockHttpServletResponse(), respondWith(200, PATTERN));
        filter.doFilter(request("/other/api/v1/products"), new MockHttpServletResponse(), respondWith(200, PATTERN));

        assertThat(registry.find("http.server.requests").timers()).hasSize(1);
        assertThat(timer().count()).isEqualTo(3);
        assertThat(timer().getId().getTag("uri")).isEqualTo(PATTERN);
    }

    @Test
    void aThrownExceptionIsCountedAsAServerErrorAndRethrown() {
        FilterChain throwing = (req, res) -> {
            throw new ServletException(new IllegalStateException("boom"));
        };

        assertThatThrownBy(() -> filter.doFilter(request("/acme/api/v1/x"), new MockHttpServletResponse(), throwing))
                .isInstanceOf(ServletException.class);

        Timer timer = timer();
        assertThat(timer.getId().getTag("status")).isEqualTo("500");
        assertThat(timer.getId().getTag("outcome")).isEqualTo("SERVER_ERROR");
        assertThat(timer.getId().getTag("exception")).isEqualTo("IllegalStateException");
        assertThat(timer.getId().getTag("error")).isEqualTo("IllegalStateException");
    }

    @Test
    void aBareRuntimeExceptionIsNamedByItsOwnClass() {
        FilterChain throwing = (req, res) -> {
            throw new IllegalArgumentException("bad");
        };

        assertThatThrownBy(() -> filter.doFilter(request("/x"), new MockHttpServletResponse(), throwing))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(timer().getId().getTag("exception")).isEqualTo("IllegalArgumentException");
    }

    @Test
    void anExceptionHandledByMvcIsNotNamedBecauseTheObservationDoesNotNameIt() throws Exception {
        FilterChain handled = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, PATTERN);
            req.setAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE, new IllegalArgumentException("bad filter"));
            ((MockHttpServletResponse) res).setStatus(400);
        };

        filter.doFilter(request("/acme/api/v1/x"), new MockHttpServletResponse(), handled);

        assertThat(timer().getId().getTag("status")).isEqualTo("400");
        assertThat(timer().getId().getTag("outcome")).isEqualTo("CLIENT_ERROR");
        assertThat(timer().getId().getTag("exception")).isEqualTo("none");
        assertThat(timer().getId().getTag("error")).isEqualTo("none");
    }

    @Test
    void anExceptionTheContainerRecordedForTheErrorDispatchIsNamed() throws Exception {
        FilterChain failed = (req, res) -> {
            req.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("rejected"));
            ((MockHttpServletResponse) res).setStatus(400);
        };

        filter.doFilter(request("/x"), new MockHttpServletResponse(), failed);

        assertThat(timer().getId().getTag("exception")).isEqualTo("IllegalStateException");
        assertThat(timer().getId().getTag("error")).isEqualTo("IllegalStateException");
    }

    @Test
    void aMethodOutsideTheStandardSetIsOneSeriesNotOnePerSpelling() throws Exception {
        for (String method : new String[] {"FOOBAR1", "FOOBAR2", "FOOBAR3"}) {
            MockHttpServletRequest odd = new MockHttpServletRequest(method, "/x");
            odd.setRequestURI("/x");
            filter.doFilter(odd, new MockHttpServletResponse(), respondWith(405, null));
        }

        assertThat(registry.find("http.server.requests").timers()).hasSize(1);
        assertThat(timer().getId().getTag("method")).isEqualTo("UNKNOWN");
        assertThat(timer().count()).isEqualTo(3);
    }

    @Test
    void everyStandardMethodKeepsItsOwnName() throws Exception {
        for (String method : new String[] {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE"}) {
            MockHttpServletRequest standard = new MockHttpServletRequest(method, "/x");
            standard.setRequestURI("/x");
            filter.doFilter(standard, new MockHttpServletResponse(), respondWith(200, "/x"));
        }

        assertThat(registry.find("http.server.requests").timers()).extracting(t -> t.getId().getTag("method"))
                .containsExactlyInAnyOrder("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");
    }

    @Test
    void anUnmatchedRequestThatIs404UsesNotFoundNotTheRawPath() throws Exception {
        filter.doFilter(request("/no/such/thing/123"), new MockHttpServletResponse(), respondWith(404, null));

        assertThat(timer().getId().getTag("uri")).isEqualTo("NOT_FOUND");
        assertThat(timer().getId().getTag("outcome")).isEqualTo("CLIENT_ERROR");
    }

    @Test
    void anUnmatchedRedirectUsesRedirection() throws Exception {
        filter.doFilter(request("/old"), new MockHttpServletResponse(), respondWith(302, null));

        assertThat(timer().getId().getTag("uri")).isEqualTo("REDIRECTION");
        assertThat(timer().getId().getTag("outcome")).isEqualTo("REDIRECTION");
    }

    @Test
    void anUnmatchedOtherResponseUsesUnknown() throws Exception {
        filter.doFilter(request("/rejected/early/456"), new MockHttpServletResponse(), respondWith(401, null));

        assertThat(timer().getId().getTag("uri")).isEqualTo("UNKNOWN");
        assertThat(timer().getId().getTag("status")).isEqualTo("401");
    }

    @Test
    void aDisabledFilterRecordsNothing() throws Exception {
        HttpRequestTimerFilter off = new HttpRequestTimerFilter(registry, false);

        off.doFilter(request("/x"), new MockHttpServletResponse(), respondWith(200, "/x"));

        assertThat(registry.find("http.server.requests").timers()).isEmpty();
    }

    @Test
    void theRequestStillReachesTheChainWhenDisabled() throws IOException, ServletException {
        HttpRequestTimerFilter off = new HttpRequestTimerFilter(registry, false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        off.doFilter(request("/x"), response, respondWith(204, "/x"));

        assertThat(response.getStatus()).isEqualTo(204);
    }

    @Test
    void aRequestRejectedBeforeRoutingGetsTheRouteItsPathWouldHaveMatched() throws Exception {
        HttpRequestTimerFilter withRoutes = new HttpRequestTimerFilter(registry, true, request -> PATTERN);

        withRoutes.doFilter(request("/unknown-proj/api/v1/products"), new MockHttpServletResponse(),
                respondWith(404, null));

        assertThat(timer().getId().getTag("uri")).isEqualTo(PATTERN);
    }

    @Test
    void aMatchedRequestNeverAsksTheRouteResolver() throws Exception {
        HttpRequestTimerFilter withRoutes = new HttpRequestTimerFilter(registry, true, request -> {
            throw new AssertionError("resolver consulted for a matched request");
        });

        withRoutes.doFilter(request("/x"), new MockHttpServletResponse(), respondWith(200, "/x"));

        assertThat(timer().getId().getTag("uri")).isEqualTo("/x");
    }

    @Test
    void anInFlightRequestIsCountedByTheActiveTimerAndReleasedAfterwards() throws Exception {
        int[] during = new int[1];
        FilterChain observing = (req, res) -> during[0] =
                registry.get("http.server.requests.active").longTaskTimer().activeTasks();

        filter.doFilter(request("/x"), new MockHttpServletResponse(), observing);

        assertThat(during[0]).isEqualTo(1);
        assertThat(registry.get("http.server.requests.active").longTaskTimer().activeTasks()).isZero();
        assertThat(registry.get("http.server.requests.active").longTaskTimer().getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsExactlyInAnyOrder("exception", "method", "org.name", "org.slug", "outcome",
                        "project.name", "status", "tenant.id", "uri");
    }

    @Test
    void aRequestTheFirewallRejectedIsNamedByTheRejection() throws Exception {
        MockHttpServletRequest rejected = request("/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RejectedRequestRecorder().handle(rejected, response, new RequestRejectedException("method not allowed"));

        filter.doFilter(rejected, response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(timer().getId().getTag("exception")).isEqualTo("RequestRejectedException");
        assertThat(timer().getId().getTag("error")).isEqualTo("RequestRejectedException");
    }
}
