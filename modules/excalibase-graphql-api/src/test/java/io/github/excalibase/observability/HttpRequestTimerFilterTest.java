package io.github.excalibase.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpRequestTimerFilterTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();

    private void serve(ObservabilityLevel level, MutableHttpRequest<?> request, HttpStatus status, Throwable thrown) {
        HttpRequestTimerFilter filter = new HttpRequestTimerFilter(registry, level, 8081);
        filter.start(request);
        filter.end(request, HttpResponse.status(status), thrown);
    }

    private Timer timer(String uri, String status) {
        return registry.find("http.server.requests").tag("uri", uri).tag("status", status).timer();
    }

    @Test
    @DisplayName("a request to the actuator port is not timed")
    void managementPortIsNotTimed() {
        MutableHttpRequest<?> request = org.mockito.Mockito.spy(HttpRequest.GET("/actuator/health"));
        org.mockito.Mockito.doReturn(new java.net.InetSocketAddress("localhost", 8081)).when(request).getServerAddress();

        serve(ObservabilityLevel.FULL, request, HttpStatus.OK, null);

        assertThat(registry.find("http.server.requests").timers()).isEmpty();
        assertThat(registry.find("http.server.requests.active").meters()).isEmpty();
    }

    @Test
    @DisplayName("an accepted WebSocket handshake is a 101, outcome informational")
    void webSocketUpgrade() {
        MutableHttpRequest<?> request = HttpRequest.GET("/proj/graphql").header("Upgrade", "websocket");

        serve(ObservabilityLevel.FULL, request, HttpStatus.OK, null);

        assertThat(timer("UNKNOWN", "101").getId().getTag("outcome")).isEqualTo("INFORMATIONAL");
    }

    @Test
    @DisplayName("an exception the server turned into an error response names the series")
    void errorResponseException() {
        MutableHttpRequest<?> request = HttpRequest.POST("/proj/api/v1/rpc/a.b.c", "{}");
        request.setAttribute(HttpAttributes.URI_TEMPLATE, "/{projectId}/api/v1/rpc/{function}");
        request.setAttribute(HttpAttributes.ERROR, new IllegalStateException("bad function"));

        serve(ObservabilityLevel.FULL, request, HttpStatus.INTERNAL_SERVER_ERROR, null);

        assertThat(timer("/{projectId}/api/v1/rpc/{function}", "500").getId().getTag("exception"))
                .isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("an exception the router recorded on the request names the series")
    void routerRecordedException() {
        MutableHttpRequest<?> request = HttpRequest.POST("/proj/api/v1/rpc/a.b.c", "{}");
        request.setAttribute(HttpAttributes.URI_TEMPLATE, "/{projectId}/api/v1/rpc/{function}");
        request.setAttribute(HttpAttributes.EXCEPTION, new IllegalArgumentException("bad function"));

        serve(ObservabilityLevel.FULL, request, HttpStatus.INTERNAL_SERVER_ERROR, null);

        assertThat(timer("/{projectId}/api/v1/rpc/{function}", "500").getId().getTag("exception"))
                .isEqualTo("IllegalArgumentException");
    }

    @Test
    @DisplayName("a matched route is timed under its template with the outcome and no exception")
    void matchedRoute() {
        MutableHttpRequest<?> request = HttpRequest.GET("/proj/api/v1/orders");
        request.setAttribute(HttpAttributes.URI_TEMPLATE, "/{projectId}/api/v1/{table}");

        serve(ObservabilityLevel.FULL, request, HttpStatus.OK, null);

        Timer timer = timer("/{projectId}/api/v1/{table}", "200");
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTag("outcome")).isEqualTo("SUCCESS");
        assertThat(timer.getId().getTag("exception")).isEqualTo("none");
        assertThat(timer.getId().getTag("tenant.id")).isEqualTo("none");
    }

    @Test
    @DisplayName("a request no route answers is the catch-all, never its raw path")
    void unmatched() {
        serve(ObservabilityLevel.FULL, HttpRequest.GET("/proj/secret-path"), HttpStatus.NOT_FOUND, null);

        assertThat(timer("/**", "404").getId().getTag("outcome")).isEqualTo("CLIENT_ERROR");
    }

    @Test
    @DisplayName("a thrown exception is named by its simple class name and answers 500")
    void thrown() {
        MutableHttpRequest<?> request = HttpRequest.POST("/proj/graphql", "{}");
        request.setAttribute(HttpAttributes.URI_TEMPLATE, "/{projectId}/graphql");

        serve(ObservabilityLevel.FULL, request, HttpStatus.OK, new IllegalStateException("x"));

        Timer timer = timer("/{projectId}/graphql", "500");
        assertThat(timer.getId().getTag("exception")).isEqualTo("IllegalStateException");
        assertThat(timer.getId().getTag("outcome")).isEqualTo("SERVER_ERROR");
    }

    @Test
    @DisplayName("an exception the HTTP layer recorded on the request names the series when none was thrown")
    void recordedException() {
        MutableHttpRequest<?> request = HttpRequest.GET("/proj/api/v1/rpc/a;b");
        request.setAttribute(HttpAttributes.URI_TEMPLATE, "/{projectId}/api/v1/rpc/{function}");
        request.setAttribute(HttpRequestFacts.EXCEPTION_ATTRIBUTE, new IllegalArgumentException("rejected"));

        serve(ObservabilityLevel.FULL, request, HttpStatus.BAD_REQUEST, null);

        assertThat(timer("/{projectId}/api/v1/rpc/{function}", "400").getId().getTag("exception"))
                .isEqualTo("IllegalArgumentException");
    }

    @Test
    @DisplayName("only standard methods name a series; others share one")
    void unknownMethod() {
        serve(ObservabilityLevel.FULL, customMethod("FOOBAR1"), HttpStatus.NOT_FOUND, null);
        serve(ObservabilityLevel.FULL, customMethod("FOOBAR2"), HttpStatus.NOT_FOUND, null);

        assertThat(registry.find("http.server.requests").tag("method", "UNKNOWN").timers()).hasSize(1);
    }

    private static MutableHttpRequest<?> customMethod(String name) {
        return HttpRequest.create(HttpMethod.CUSTOM, "/x", name);
    }

    @Test
    @DisplayName("lite times requests with the same series as full")
    void liteTimes() {
        serve(ObservabilityLevel.LITE, HttpRequest.GET("/x"), HttpStatus.NOT_FOUND, null);

        assertThat(timer("/**", "404").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("off records nothing")
    void offRecordsNothing() {
        serve(ObservabilityLevel.OFF, HttpRequest.GET("/x"), HttpStatus.OK, null);

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    @DisplayName("every standard method keeps its own name")
    void everyStandardMethodKeepsItsOwnName() {
        for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT,
                HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS, HttpMethod.TRACE}) {
            serve(ObservabilityLevel.FULL, HttpRequest.create(method, "/x"), HttpStatus.OK, null);
        }

        assertThat(registry.find("http.server.requests").timers()).extracting(t -> t.getId().getTag("method"))
                .containsExactlyInAnyOrder("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");
    }

    @Test
    @DisplayName("an unmatched redirect uses REDIRECTION, not the raw path")
    void anUnmatchedRedirectUsesRedirection() {
        serve(ObservabilityLevel.FULL, HttpRequest.GET("/old"), HttpStatus.FOUND, null);

        assertThat(timer("REDIRECTION", "302").getId().getTag("outcome")).isEqualTo("REDIRECTION");
    }

    @Test
    @DisplayName("an unmatched response that is neither a 404 nor a redirect uses UNKNOWN")
    void anUnmatchedOtherResponseUsesUnknown() {
        serve(ObservabilityLevel.FULL, HttpRequest.GET("/odd"), HttpStatus.OK, null);

        assertThat(timer("UNKNOWN", "200")).isNotNull();
    }

    @Test
    @DisplayName("an exception an application handler answered is not named: the series says none")
    void anExceptionHandledByTheApplicationIsNotNamed() {
        MutableHttpRequest<?> request = HttpRequest.POST("/proj/graphql", "{}");
        request.setAttribute(HttpAttributes.URI_TEMPLATE, "/{projectId}/graphql");

        serve(ObservabilityLevel.FULL, request, HttpStatus.BAD_REQUEST, null);

        assertThat(timer("/{projectId}/graphql", "400").getId().getTag("exception")).isEqualTo("none");
    }

    @Test
    @DisplayName("different paths of one route share one timer")
    void uriIsThePatternSoDifferentPathsShareOneTimer() {
        for (String path : new String[] {"/a/api/v1/orders", "/b/api/v1/orders"}) {
            MutableHttpRequest<?> request = HttpRequest.GET(path);
            request.setAttribute(HttpAttributes.URI_TEMPLATE, "/{projectId}/api/v1/{table}");
            serve(ObservabilityLevel.FULL, request, HttpStatus.OK, null);
        }

        assertThat(timer("/{projectId}/api/v1/{table}", "200").count()).isEqualTo(2);
    }

    @Test
    @DisplayName("an in-flight request is counted by the active timer and released afterwards")
    void anInFlightRequestIsCountedByTheActiveTimerAndReleasedAfterwards() {
        HttpRequestTimerFilter filter = new HttpRequestTimerFilter(registry, ObservabilityLevel.FULL, 8081);
        MutableHttpRequest<?> request = HttpRequest.GET("/x");

        filter.start(request);
        assertThat(registry.get("http.server.requests.active").longTaskTimer().activeTasks()).isEqualTo(1);
        filter.end(request, HttpResponse.ok(), null);

        assertThat(registry.get("http.server.requests.active").longTaskTimer().activeTasks()).isZero();
        assertThat(registry.get("http.server.requests.active").longTaskTimer().getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsExactlyInAnyOrder("exception", "method", "org.name", "org.slug", "outcome",
                        "project.name", "status", "tenant.id", "uri");
    }

    @Test
    @DisplayName("the series is labelled with the tenant, org and project names of the request's token")
    void theSeriesLabelsTenantsExactlyAsBefore() {
        MutableHttpRequest<?> request = HttpRequest.GET("/acme/api/v1/customers");
        request.setAttribute(HttpAttributes.URI_TEMPLATE, "/{projectId}/api/v1/{table}");
        request.setAttribute(io.github.excalibase.security.SecurityConstants.JWT_CLAIMS_ATTR,
                new io.github.excalibase.security.JwtClaims("u1", "proj-1", "acme-org", "Shop", "Acme Inc", "user",
                        "a@b.c", null, 0));

        serve(ObservabilityLevel.FULL, request, HttpStatus.OK, null);

        Timer timer = timer("/{projectId}/api/v1/{table}", "200");
        assertThat(timer.getId().getTag("tenant.id")).isEqualTo("proj-1");
        assertThat(timer.getId().getTag("org.slug")).isEqualTo("acme-org");
        assertThat(timer.getId().getTag("project.name")).isEqualTo("Shop");
        assertThat(timer.getId().getTag("org.name")).isEqualTo("Acme Inc");
        assertThat(timer.getId().getTag("method")).isEqualTo("GET");
        assertThat(timer.getId().getTag("outcome")).isEqualTo("SUCCESS");
        assertThat(timer.getId().getTag("exception")).isEqualTo("none");
    }
}
