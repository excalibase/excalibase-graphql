package io.github.excalibase.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import io.github.excalibase.support.RawWebSocket;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteInfo;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No path is served without a project id, on any surface. The cases are generated from the router's own route
 * list, so a route added without a project in its path fails here without anyone adding a case.
 */
class ProjectRequiredIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final String PROJECT_VARIABLE = "/{projectId}";
    private static final Pattern VARIABLE = Pattern.compile("\\{[^}]+}");
    private static final String NOT_FOUND_CODE = "\"code\":\"project_not_found\"";

    @Inject
    Router router;

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.unreachableDatabase(PROJECT);
    }

    private record RouteCase(String method, String template) {

        /** The path of the route with its project segment left out. */
        String withoutProject() {
            return fill(template.substring(PROJECT_VARIABLE.length()));
        }

        String withProject() {
            return fill(PROJECT_VARIABLE.equals(template.substring(0, PROJECT_VARIABLE.length()))
                    ? "/" + PROJECT + template.substring(PROJECT_VARIABLE.length()) : template);
        }

        private static String fill(String path) {
            return VARIABLE.matcher(path).replaceAll("x");
        }
    }

    private List<UriRouteInfo<?, ?>> routes() {
        return router.uriRoutes().filter(route -> !isManagement(route.getUriMatchTemplate().toString())).toList();
    }

    private static boolean isManagement(String template) {
        return template.equals("/actuator") || template.startsWith("/actuator/");
    }

    @Test
    @DisplayName("every route the engine serves starts with the project id")
    void everyRouteNamesAProject() {
        List<String> without = new ArrayList<>();
        routes().forEach(route -> {
            String template = route.getUriMatchTemplate().toString();
            if (!template.startsWith(PROJECT_VARIABLE + "/")) {
                without.add(route.getHttpMethodName() + " " + template);
            }
        });

        assertThat(routes()).isNotEmpty();
        assertThat(without).as("routes served without a project id").isEmpty();
    }

    @Test
    @DisplayName("each route, asked without its project, is a 404 project_not_found, by any method and as a preflight")
    void routesWithoutAProjectAreNotFound() {
        List<String> served = new ArrayList<>();
        routes().stream().filter(route -> route.getUriMatchTemplate().toString().startsWith(PROJECT_VARIABLE + "/"))
                .map(route -> new RouteCase(route.getHttpMethodName(), route.getUriMatchTemplate().toString()))
                .forEach(route -> {
                    String path = route.withoutProject();
                    for (String method : List.of(route.method(), "GET", "POST", "PUT", "PATCH", "DELETE")) {
                        refused(served, method + " " + path,
                                http.send(method, path, method.equals("GET") ? null : "{}",
                                        "Content-Type", "application/json"));
                    }
                    refused(served, "OPTIONS " + path, http.send("OPTIONS", path, null, "Origin", "https://app.example.com",
                            "Access-Control-Request-Method", "POST"));
                });

        assertThat(served).as("answers that were not a 404 project_not_found").isEmpty();
    }

    @Test
    @DisplayName("a WebSocket upgrade on a route's path without its project is a 404")
    void upgradesWithoutAProjectAreNotFound() throws IOException {
        List<String> upgraded = new ArrayList<>();
        for (UriRouteInfo<?, ?> route : routes()) {
            String template = route.getUriMatchTemplate().toString();
            if (!template.startsWith(PROJECT_VARIABLE + "/")) {
                continue;
            }
            String path = new RouteCase("GET", template).withoutProject();
            try (RawWebSocket socket = RawWebSocket.connect(http.base(), path, null)) {
                if (!socket.handshakeAnswer().startsWith("HTTP/1.1 404")) {
                    upgraded.add(path + " -> " + socket.handshakeAnswer().lines().findFirst().orElse(""));
                }
            }
        }

        assertThat(upgraded).as("upgrades not refused with a 404").isEmpty();
    }

    @Test
    @DisplayName("paths that are no route at all, and have no project, are the same 404")
    void anyPathWithoutAProjectIsNotFound() {
        List<String> served = new ArrayList<>();
        for (String path : List.of("/", "/graphql", "/api/v1", "/api/v1/customer", "/health", "/nothing", "/metrics",
                "/openapi.json", "/graphql/")) {
            refused(served, "GET " + path, http.get(path));
        }

        assertThat(served).as("answers that were not a 404 project_not_found").isEmpty();
    }

    @Test
    @DisplayName("with its project, a route is not answered as an unknown project")
    void routesWithAProjectAreRouted() {
        List<String> unknown = new ArrayList<>();
        routes().stream().filter(route -> route.getUriMatchTemplate().toString().startsWith(PROJECT_VARIABLE + "/"))
                .forEach(route -> {
                    String path = new RouteCase(route.getHttpMethodName(), route.getUriMatchTemplate().toString()).withProject();
                    HttpAnswer answer = http.send(route.getHttpMethodName(), path, "{}", "Content-Type", "application/json");
                    if (answer.body().contains(NOT_FOUND_CODE)) {
                        unknown.add(path);
                    }
                });

        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName("whatever follows the project, an unserved project is refused and the served one is not")
    void theLeadingSegmentIsAlwaysChecked() {
        List<String> served = new ArrayList<>();
        for (String suffix : List.of("/held", "/probe/ids", "/anything/at/all", "/graphql/extra")) {
            refused(served, "GET /other-proj" + suffix, http.get("/other-proj" + suffix));
        }

        assertThat(served).as("paths under an unserved project that were not refused").isEmpty();
        assertThat(http.get("/" + PROJECT + "/anything/at/all").body()).doesNotContain(NOT_FOUND_CODE);
    }

    @Test
    @DisplayName("the actuator is the management surface and is left as it is")
    void actuatorIsNotAProjectRoute() {
        assertThat(http.get("/actuator/health").body()).doesNotContain(NOT_FOUND_CODE);
    }

    private static void refused(List<String> served, String what, HttpAnswer answer) {
        boolean noBody = what.startsWith("HEAD ");
        if (answer.status() != 404 || (!noBody && !answer.body().contains(NOT_FOUND_CODE))) {
            served.add(what + " -> " + answer.status());
        }
    }
}
