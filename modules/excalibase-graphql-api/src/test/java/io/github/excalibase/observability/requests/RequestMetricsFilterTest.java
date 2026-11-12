package io.github.excalibase.observability.requests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.security.KnownProjectFilter;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestMetricsFilterTest {

    private static final String PROJECT = "proj-a";

    private final RequestMetrics metrics = new RequestMetrics(10);
    private final RequestMetricsFilter filter = new RequestMetricsFilter(metrics);

    private static MutableHttpRequest<?> graphql() {
        return HttpRequest.POST("/proj-a/graphql", "{}");
    }

    private static void markKnown(HttpRequest<?> request) {
        request.setAttribute(KnownProjectFilter.KNOWN_PROJECT_ATTR, PROJECT);
    }

    private void serve(HttpRequest<?> request, HttpStatus status, boolean known, Throwable thrown) {
        filter.filter(request);
        if (known) {
            markKnown(request);
        }
        filter.record(request, HttpResponse.status(status), thrown);
    }

    @Test
    @DisplayName("the main port does not serve the scrape at /metrics")
    void mainPortPassesMetricsPathThrough() {
        assertThat(filter.filter(HttpRequest.GET("/metrics"))).isNull();
        assertThat(metrics.scrape()).doesNotContain("excalibase_requests_seconds_count");
    }

    @Test
    @DisplayName("a request for a known project is timed with its family and outcome")
    void recordsKnownProject() {
        serve(graphql(), HttpStatus.OK, true, null);
        serve(HttpRequest.GET("/proj-a/api/v1/orders"), HttpStatus.NOT_FOUND, true, null);

        assertThat(metrics.scrape())
                .contains("excalibase_requests_seconds_count{family=\"graphql\",outcome=\"ok\",project=\"proj-a\"} 1")
                .contains("excalibase_requests_seconds_count{family=\"rest\",outcome=\"client_error\",project=\"proj-a\"} 1");
    }

    @Test
    @DisplayName("a GraphQL answer of 200 carrying errors is a client error")
    void graphqlErrorsInOk() {
        MutableHttpRequest<?> request = graphql();
        request.setAttribute(RequestMetrics.GRAPHQL_ERRORS_ATTR, Boolean.TRUE);

        serve(request, HttpStatus.OK, true, null);

        assertThat(metrics.scrape()).contains(
                "excalibase_requests_seconds_count{family=\"graphql\",outcome=\"client_error\",project=\"proj-a\"} 1");
    }

    @Test
    @DisplayName("a request the known-project check did not pass is not recorded")
    void unknownProjectNotRecorded() {
        serve(HttpRequest.POST("/random-xyz/graphql", "{}"), HttpStatus.NOT_FOUND, false, null);

        assertThat(metrics.scrape()).doesNotContain("random-xyz").doesNotContain("excalibase_requests_seconds_count");
    }

    @Test
    @DisplayName("WebSocket upgrades are not counted as HTTP requests")
    void upgradesSkipped() {
        MutableHttpRequest<?> upgrade = HttpRequest.GET("/proj-a/graphql").header("Upgrade", "websocket");

        serve(upgrade, HttpStatus.SWITCHING_PROTOCOLS, true, null);

        assertThat(metrics.scrape()).doesNotContain("excalibase_requests_seconds_count");
    }

    @Test
    @DisplayName("a request whose handling throws is a server error")
    void thrownIsServerError() {
        serve(graphql(), HttpStatus.OK, true, new IllegalStateException("boom"));

        assertThat(metrics.scrape()).contains(
                "excalibase_requests_seconds_count{family=\"graphql\",outcome=\"server_error\",project=\"proj-a\"} 1");
    }

    @Test
    @DisplayName("the recorded duration is a real elapsed time")
    void durationIsRecorded() {
        metrics.recordRequest(PROJECT, RequestFamily.GRAPHQL, RequestOutcome.OK, Duration.ofMillis(3));

        assertThat(metrics.scrape()).contains("excalibase_requests_seconds_sum{family=\"graphql\"");
    }
}
