package io.github.excalibase.observability.requests;

import io.micrometer.core.instrument.Metrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RequestMetricsTest {

    private static final Duration TWELVE_MILLIS = Duration.ofMillis(12);

    @Test
    @DisplayName("a request is a timer series tagged by project, family and outcome")
    void recordsRequestSeries() {
        RequestMetrics metrics = new RequestMetrics(10);

        metrics.recordRequest("proj-a", RequestFamily.GRAPHQL, RequestOutcome.OK, TWELVE_MILLIS);
        metrics.recordRequest("proj-a", RequestFamily.REST, RequestOutcome.SERVER_ERROR, TWELVE_MILLIS);

        String scrape = metrics.scrape();
        assertThat(scrape).contains(
                "excalibase_requests_seconds_count{family=\"graphql\",outcome=\"ok\",project=\"proj-a\"} 1",
                "excalibase_requests_seconds_count{family=\"rest\",outcome=\"server_error\",project=\"proj-a\"} 1",
                "excalibase_requests_seconds_sum{family=\"graphql\",outcome=\"ok\",project=\"proj-a\"} 0.012");
    }

    @Test
    @DisplayName("buckets are the fixed service-level objectives, the same on every pod")
    void fixedBuckets() {
        RequestMetrics metrics = new RequestMetrics(10);

        metrics.recordRequest("proj-a", RequestFamily.GRAPHQL, RequestOutcome.OK, TWELVE_MILLIS);

        List<String> buckets = metrics.scrape().lines()
                .filter(line -> line.startsWith("excalibase_requests_seconds_bucket"))
                .map(line -> line.replaceAll(".*le=\"([^\"]+)\".*", "$1"))
                .toList();
        assertThat(buckets).containsExactly("0.005", "0.01", "0.025", "0.05", "0.1", "0.25", "0.5",
                "1.0", "2.5", "5.0", "10.0", "+Inf");
        assertThat(metrics.scrape())
                .contains("excalibase_requests_seconds_bucket{family=\"graphql\",outcome=\"ok\",project=\"proj-a\",le=\"0.01\"} 0")
                .contains("excalibase_requests_seconds_bucket{family=\"graphql\",outcome=\"ok\",project=\"proj-a\",le=\"0.025\"} 1")
                .doesNotContain("quantile=");
    }

    @Test
    @DisplayName("projects beyond the cap are not recorded and count as dropped")
    void capDropsNewProjects() {
        RequestMetrics metrics = new RequestMetrics(1);

        metrics.recordRequest("proj-a", RequestFamily.GRAPHQL, RequestOutcome.OK, TWELVE_MILLIS);
        metrics.recordRequest("proj-b", RequestFamily.GRAPHQL, RequestOutcome.OK, TWELVE_MILLIS);
        metrics.messageDelivered("proj-c");
        metrics.recordRequest("proj-a", RequestFamily.REST, RequestOutcome.OK, TWELVE_MILLIS);

        String scrape = metrics.scrape();
        assertThat(scrape).contains("project=\"proj-a\"")
                .doesNotContain("proj-b")
                .doesNotContain("proj-c")
                .contains("excalibase_request_metrics_dropped_total 2.0");
    }

    @Test
    @DisplayName("open connections go up on open, down on close, never below zero")
    void connectionGauge() {
        RequestMetrics metrics = new RequestMetrics(10);

        assertThat(metrics.connectionOpened("proj-a")).isTrue();
        metrics.connectionOpened("proj-a");
        metrics.connectionClosed("proj-a");

        assertThat(metrics.scrape())
                .contains("excalibase_realtime_connections{project=\"proj-a\"} 1")
                .contains("excalibase_realtime_connections_opened_total{project=\"proj-a\"} 2.0");

        metrics.connectionClosed("proj-a");
        metrics.connectionClosed("proj-a");
        metrics.connectionClosed("proj-never");

        assertThat(metrics.scrape())
                .contains("excalibase_realtime_connections{project=\"proj-a\"} 0")
                .doesNotContain("proj-never");
    }

    @Test
    @DisplayName("a connection beyond the cap is refused admission and dropped")
    void connectionBeyondCap() {
        RequestMetrics metrics = new RequestMetrics(1);
        metrics.connectionOpened("proj-a");

        assertThat(metrics.connectionOpened("proj-b")).isFalse();
        assertThat(metrics.scrape()).doesNotContain("proj-b")
                .contains("excalibase_request_metrics_dropped_total 1.0");
    }

    @Test
    @DisplayName("delivered change events count per project")
    void messages() {
        RequestMetrics metrics = new RequestMetrics(10);

        metrics.messageDelivered("proj-a");
        metrics.messageDelivered("proj-a");

        assertThat(metrics.scrape()).contains("excalibase_realtime_messages_total{project=\"proj-a\"} 2.0");
    }

    @Test
    @DisplayName("the info series names this process's instance; the start time series carries when it began")
    void instanceInfo() {
        RequestMetrics metrics = new RequestMetrics(10);
        String instanceLabel = "{instance=\"" + metrics.instanceId() + "\"}";

        String scrape = metrics.scrape();
        assertThat(scrape).contains("excalibase_request_metrics_info" + instanceLabel + " 1");
        String startLine = scrape.lines()
                .filter(line -> line.startsWith("excalibase_request_metrics_start_time_seconds" + instanceLabel))
                .findFirst().orElseThrow();
        double startSeconds = Double.parseDouble(startLine.substring(startLine.lastIndexOf(' ') + 1));
        assertThat(startSeconds).isGreaterThan(1.7e9);
        assertThat(new RequestMetrics(10).instanceId()).isNotEqualTo(metrics.instanceId());
    }

    @Test
    @DisplayName("nothing is registered in the global registry")
    void dedicatedRegistry() {
        RequestMetrics metrics = new RequestMetrics(10);
        metrics.recordRequest("proj-a", RequestFamily.GRAPHQL, RequestOutcome.OK, TWELVE_MILLIS);

        assertThat(Metrics.globalRegistry.find("excalibase.requests").meters())
                .isEmpty();
    }

    @Test
    @DisplayName("outcome: 5xx server error, 4xx or GraphQL errors client error, else ok")
    void outcomes() {
        assertThat(RequestOutcome.of(200, false)).isEqualTo(RequestOutcome.OK);
        assertThat(RequestOutcome.of(200, true)).isEqualTo(RequestOutcome.CLIENT_ERROR);
        assertThat(RequestOutcome.of(404, false)).isEqualTo(RequestOutcome.CLIENT_ERROR);
        assertThat(RequestOutcome.of(503, true)).isEqualTo(RequestOutcome.SERVER_ERROR);
        assertThat(RequestOutcome.of(500, false).tag()).isEqualTo("server_error");
    }

    @Test
    @DisplayName("family: /graphql is graphql, /api/v1 is rest, anything else is neither")
    void families() {
        assertThat(RequestFamily.of("/proj-a/graphql")).contains(RequestFamily.GRAPHQL);
        assertThat(RequestFamily.of("/proj-a/api/v1/orders")).contains(RequestFamily.REST);
        assertThat(RequestFamily.of("/actuator/health")).isEqualTo(Optional.empty());
        assertThat(RequestFamily.of("/graphql")).isEmpty();
        assertThat(RequestFamily.REST.tag()).isEqualTo("rest");
    }
}
