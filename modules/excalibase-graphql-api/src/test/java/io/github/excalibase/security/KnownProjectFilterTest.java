package io.github.excalibase.security;

import io.github.excalibase.service.VaultCredentialException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
