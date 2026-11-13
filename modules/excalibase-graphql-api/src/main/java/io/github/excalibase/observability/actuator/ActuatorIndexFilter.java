package io.github.excalibase.observability.actuator;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code /actuator}: links to the exposed endpoints, named as Spring Boot named them. Endpoints cannot sit on the
 * bare path, so this answers it, on the management port only.
 */
@ServerFilter(ActuatorIndexFilter.PATH)
public class ActuatorIndexFilter {

    static final String PATH = "/actuator";

    private final EndpointExposure exposure;
    private final int managementPort;

    ActuatorIndexFilter(EndpointExposure exposure, @Value("${endpoints.all.port:-1}") int managementPort) {
        this.exposure = exposure;
        this.managementPort = managementPort;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> index(HttpRequest<?> request) {
        if (request.getMethod() != HttpMethod.GET || request.getServerAddress().getPort() != managementPort) {
            return null;
        }
        return HttpResponse.ok(links("http://" + authority(request) + PATH)).contentType(MediaType.of(ActuatorEndpoint.JSON));
    }

    private static String authority(HttpRequest<?> request) {
        String host = request.getHeaders().get("Host");
        return host != null ? host : request.getServerAddress().getHostString() + ":" + request.getServerAddress().getPort();
    }

    private Map<String, Object> links(String self) {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", link(self, false));
        addLink(links, "health", self + "/health", false);
        addLink(links, "health-path", self + "/health/{*path}", true);
        addLink(links, "info", self + "/info", false);
        addLink(links, "metrics", self + "/metrics", false);
        addLink(links, "metrics-requiredMetricName", self + "/metrics/{requiredMetricName}", true);
        addLink(links, "prometheus", self + "/prometheus", false);
        return Map.of("_links", links);
    }

    private void addLink(Map<String, Object> links, String name, String href, boolean templated) {
        if (exposure.exposes(name.replaceFirst("-.*", ""))) {
            links.put(name, link(href, templated));
        }
    }

    private static Map<String, Object> link(String href, boolean templated) {
        return Map.of("href", href, "templated", templated);
    }
}
