package io.github.excalibase.http;

import io.github.excalibase.admin.AdminGuard;
import io.github.excalibase.admin.AdminSettings;
import io.github.excalibase.security.KnownProjectFilter;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.simple.SimpleHttpHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The filter's own answers, before any project filter could give a 404 of its own. */
class AdminPortFilterTest {

    private static final String SECRET = "filter-secret-0123456789-abcdefghijklmnop";
    private static final int ADMIN = 10001;

    private final AdminGuard guard = new AdminGuard(AdminGuard.Secret.of(SECRET),
            new AdminGuard.FailedAttempts(3, Duration.ofMinutes(1), 10, System::nanoTime));
    private final AdminSettings on = new AdminSettings(true, ADMIN, AdminGuard.Secret.of(SECRET));

    private static HttpRequest<?> request(String path, int port, String secret) {
        HttpRequest<?> request = mock(HttpRequest.class);
        when(request.getPath()).thenReturn(path);
        when(request.getUri()).thenReturn(URI.create(path));
        when(request.getServerAddress()).thenReturn(new InetSocketAddress("localhost", port));
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 40000));
        when(request.getAttributes()).thenReturn(new MutableConvertibleValuesMap<>());
        SimpleHttpHeaders headers = new SimpleHttpHeaders(new LinkedHashMap<>(), null);
        if (secret != null) {
            headers.add(AdminGuard.SECRET_HEADER, secret);
        }
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    private static String body(HttpResponse<?> response) {
        return String.valueOf(response.body());
    }

    @Test
    @DisplayName("an admin path off the admin port is the filter's own 404, never reaching a project filter")
    void adminPathOffTheAdminPort() {
        for (int port : new int[] {10000, 8081}) {
            HttpResponse<?> response = new AdminPortFilter(on, guard).filter(request("/_admin/v1/metadata", port, SECRET));

            assertThat(response.code()).isEqualTo(404);
            assertThat(body(response)).contains("/_admin/v1/metadata").doesNotContain("project_not_found");
        }
    }

    @Test
    @DisplayName("with the console off an admin path is the filter's 404 on every port")
    void consoleOff() {
        AdminPortFilter filter = new AdminPortFilter(AdminSettings.OFF, null);

        assertThat(filter.filter(request("/_admin/v1/metadata", ADMIN, SECRET)).code()).isEqualTo(404);
        assertThat(filter.filter(request("/_admin/v1/metadata", 10000, SECRET)).code()).isEqualTo(404);
    }

    @Test
    @DisplayName("a public path off the admin port is left to the other filters")
    void publicPathPasses() {
        assertThat(new AdminPortFilter(on, guard).filter(request("/proj/graphql", 10000, null))).isNull();
    }

    @Test
    @DisplayName("a public path on the admin port is the filter's own 404")
    void publicPathOnTheAdminPort() {
        HttpResponse<?> response = new AdminPortFilter(on, guard).filter(request("/proj/graphql", ADMIN, SECRET));

        assertThat(response.code()).isEqualTo(404);
        assertThat(body(response)).doesNotContain("project_not_found");
    }

    @Test
    @DisplayName("on the admin port the right secret passes and marks the request; a wrong or missing one is a 401")
    void guardOnTheAdminPort() {
        HttpRequest<?> good = request("/_admin/v1/metadata", ADMIN, SECRET);

        assertThat(new AdminPortFilter(on, guard).filter(good)).isNull();
        assertThat(good.getAttributes().contains(KnownProjectFilter.ADMIN_ATTR)).isTrue();
        assertThat(new AdminPortFilter(on, guard).filter(request("/_admin/v1/metadata", ADMIN, "nope")).code()).isEqualTo(401);
        assertThat(new AdminPortFilter(on, guard).filter(request("/_admin/v1/metadata", ADMIN, null)).code()).isEqualTo(401);
    }
}
