package io.github.excalibase.cors;

import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The data plane's CORS rule: a refused preflight is the browser's signal to
 * block, while an actual request is always served and only a listed origin is
 * granted {@code Access-Control-Allow-Origin}. Auth is a bearer token, so the
 * Origin header alone gives the server nothing to refuse on.
 */
class CorsFilterTest {

    private static final String LISTED = "https://app.example.com";
    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";

    private static CorsFilter filterFor(CorsRules rules) {
        return new CorsFilter(new ProjectCorsConfigurationSource(null, rules));
    }

    private final CorsFilter filter = filterFor(ProjectCorsConfigurationSource.configFor(List.of(LISTED)));

    private static MutableHttpRequest<?> actual(HttpMethod method, String origin) {
        return HttpRequest.create(method, "/p1/graphql")
                .header("Host", "api.excalibase.io")
                .header("Origin", origin);
    }

    private static MutableHttpRequest<?> preflight(String origin) {
        return actual(HttpMethod.OPTIONS, origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type");
    }

    /** What the client sees: the filter's own answer, or the served response after the response hook. */
    private static HttpResponse<?> answer(CorsFilter filter, MutableHttpRequest<?> request) {
        HttpResponse<?> early = filter.filter(request);
        if (early != null) {
            return early;
        }
        MutableHttpResponse<?> served = HttpResponse.ok();
        filter.filter(request, served);
        return served;
    }

    @Test
    @DisplayName("a listed origin's actual request is served with the origin echoed")
    void listedActualRequestIsGranted() {
        HttpResponse<?> response = answer(filter, actual(HttpMethod.POST, LISTED));

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.getHeaders().get(ALLOW_ORIGIN)).isEqualTo(LISTED);
        assertThat(response.getHeaders().get("Access-Control-Expose-Headers")).contains("Content-Range");
        assertThat(response.getHeaders().get("Access-Control-Allow-Credentials")).isNull();
        assertThat(response.getHeaders().getAll("Vary")).contains("Origin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "capacitor://localhost", "tauri://localhost", "null",
            "http://localhost:5173"})
    @DisplayName("an unlisted origin's actual request is served without any grant")
    void unlistedActualRequestIsServedWithoutGrant(String origin) {
        HttpResponse<?> response = answer(filter, actual(HttpMethod.POST, origin));

        assertThat(response.code()).as("served, not refused for its Origin").isEqualTo(200);
        assertThat(response.getHeaders().get(ALLOW_ORIGIN)).isNull();
        assertThat(response.getHeaders().getAll("Vary")).contains("Origin");
    }

    @Test
    @DisplayName("a listed origin's actual request with a method outside the list is still served")
    void listedOriginUnusualMethodIsServed() {
        HttpResponse<?> response = answer(filter, actual(HttpMethod.HEAD, LISTED));

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.getHeaders().get(ALLOW_ORIGIN)).isEqualTo(LISTED);
    }

    @Test
    @DisplayName("a listed origin's preflight is answered with the grant")
    void listedPreflightIsGranted() {
        HttpResponse<?> response = answer(filter, preflight(LISTED));

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.getHeaders().get(ALLOW_ORIGIN)).isEqualTo(LISTED);
        assertThat(response.getHeaders().get("Access-Control-Allow-Methods")).contains("POST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "capacitor://localhost", "null"})
    @DisplayName("an unlisted origin's preflight is refused with no CORS headers, so the browser blocks")
    void unlistedPreflightIsRefused(String origin) {
        HttpResponse<?> response = answer(filter, preflight(origin));

        assertThat(response.code()).isEqualTo(403);
        assertThat(response.getHeaders().get(ALLOW_ORIGIN)).isNull();
        assertThat(response.getHeaders().get("Access-Control-Allow-Methods")).isNull();
    }

    @Test
    @DisplayName("a configuration that allows credentials keeps granting them on actual requests")
    void credentialedConfigurationGrantsCredentials() {
        CorsFilter credentialed = filterFor(new CorsRules(List.of(LISTED), List.of("GET", "POST"),
                List.of(CorsRules.ALL), List.of("Content-Range"), true, 3600));

        HttpResponse<?> response = answer(credentialed, actual(HttpMethod.GET, LISTED));

        assertThat(response.getHeaders().get("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    @DisplayName("a request without Origin is not CORS and passes untouched")
    void noOriginIsUntouched() {
        MutableHttpRequest<?> request = HttpRequest.POST("/p1/graphql", "{}");

        HttpResponse<?> response = answer(filter, request);

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.getHeaders().get(ALLOW_ORIGIN)).isNull();
    }

    private static CorsFilter countingFilter(java.util.concurrent.atomic.AtomicInteger lookups) {
        ProjectCorsConfigurationSource source = new ProjectCorsConfigurationSource(project -> {
            lookups.incrementAndGet();
            return List.of(LISTED);
        }, ProjectCorsConfigurationSource.configFor(List.of()));
        return new CorsFilter(source);
    }

    @Test
    @DisplayName("the project's origins are looked up for every project request, with or without an Origin header")
    void everyProjectRequestLooksUpTheOrigins() {
        java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
        CorsFilter counting = countingFilter(lookups);

        answer(counting, HttpRequest.create(HttpMethod.GET, "/p1/api/v1/customer").header("Host", "api.excalibase.io"));
        answer(counting, actual(HttpMethod.POST, "http://api.excalibase.io"));
        answer(counting, actual(HttpMethod.POST, LISTED));

        assertThat(lookups).hasValue(3);
    }

    @Test
    @DisplayName("a request with no Origin is served with no CORS headers whatever the lookup says")
    void noOriginGetsNoHeaders() {
        HttpResponse<?> response = answer(countingFilter(new java.util.concurrent.atomic.AtomicInteger()),
                HttpRequest.create(HttpMethod.GET, "/p1/api/v1/customer").header("Host", "api.excalibase.io"));

        assertThat(response.getHeaders().contains(ALLOW_ORIGIN)).isFalse();
        assertThat(response.getHeaders().contains("Vary")).isFalse();
    }
}
