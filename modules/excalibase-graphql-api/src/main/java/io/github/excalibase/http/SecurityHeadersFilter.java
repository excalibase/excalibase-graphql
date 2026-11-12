package io.github.excalibase.http;

import io.micronaut.core.order.Ordered;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The response headers the servlet stack's security chain added to every answer from the application, on both
 * ports: no sniffing, no framing, no caching, and the CORS {@code Vary} values. A header a handler already set
 * is kept.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class SecurityHeadersFilter implements Ordered {

    /** Behind the firewall (-990), so a refused request does not carry them. */
    public static final int ORDER = -950;

    private static final Map<String, String> STATIC = Map.of(
            "X-Content-Type-Options", "nosniff",
            "X-XSS-Protection", "0",
            "X-Frame-Options", "DENY");
    private static final Map<String, String> NO_CACHE = Map.of(
            "Cache-Control", "no-cache, no-store, max-age=0, must-revalidate",
            "Pragma", "no-cache",
            "Expires", "0");
    private static final List<String> VARY = List.of("Origin", "Access-Control-Request-Method",
            "Access-Control-Request-Headers");

    @Override
    public int getOrder() {
        return ORDER;
    }

    @ResponseFilter
    public void harden(MutableHttpResponse<?> response) {
        STATIC.forEach((name, value) -> addIfAbsent(response, name, value));
        if (NO_CACHE.keySet().stream().noneMatch(name -> response.getHeaders().contains(name))) {
            NO_CACHE.forEach(response::header);
        }
        addVary(response);
    }

    private static void addIfAbsent(MutableHttpResponse<?> response, String name, String value) {
        if (!response.getHeaders().contains(name)) {
            response.header(name, value);
        }
    }

    private static void addVary(MutableHttpResponse<?> response) {
        Set<String> present = response.getHeaders().getAll("Vary").stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        VARY.stream().filter(value -> !present.contains(value.toLowerCase(Locale.ROOT)))
                .forEach(value -> response.getHeaders().add("Vary", value));
    }
}
