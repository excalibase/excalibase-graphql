package io.github.excalibase.observability;

import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import java.util.Locale;
import java.util.Set;

/**
 * What a finished request looks like to monitoring, named as the earlier build named it: the method, the status,
 * the route template (never the raw path), the outcome class and the exception, with the tenant labels.
 * The metric and the span of a request are built from the same facts, so they never disagree.
 */
public record HttpRequestFacts(String method, int status, String uri, String routePattern, String exception,
                               TenantTags tenant) {

    /** An exception the HTTP layer answered itself (a rejected request); the filters name it when none was thrown. */
    public static final String EXCEPTION_ATTRIBUTE = "excalibase.http.exception";

    public static final String NONE = "none";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String CATCH_ALL = "/**";
    private static final Set<String> STANDARD_METHODS =
            Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

    public static HttpRequestFacts of(HttpRequest<?> request, int answeredStatus, Throwable thrown) {
        Throwable failure = thrown != null ? thrown
                : request.getAttribute(EXCEPTION_ATTRIBUTE, Throwable.class).orElse(null);
        int status = failure != null && answeredStatus < 400 ? 500 : upgradedStatus(request, answeredStatus);
        String pattern = routePattern(request, status);
        return new HttpRequestFacts(method(request), status, uri(pattern, status), pattern, exceptionName(failure),
                TenantTags.of(request));
    }

    /** A WebSocket handshake that was accepted switched protocols, whatever placeholder status the filter chain saw. */
    private static int upgradedStatus(HttpRequest<?> request, int answeredStatus) {
        boolean upgrade = "websocket".equalsIgnoreCase(request.getHeaders().get("Upgrade"));
        return upgrade && answeredStatus == 200 ? 101 : answeredStatus;
    }

    /** Only the standard methods name a series; anything else is one series, so a client cannot mint more. */
    public static String method(HttpRequest<?> request) {
        String method = request.getMethodName();
        return method != null && STANDARD_METHODS.contains(method) ? method : UNKNOWN;
    }

    public String outcome() {
        return switch (status / 100) {
            case 1 -> "INFORMATIONAL";
            case 2 -> "SUCCESS";
            case 3 -> "REDIRECTION";
            case 4 -> "CLIENT_ERROR";
            case 5 -> "SERVER_ERROR";
            default -> UNKNOWN;
        };
    }

    /** The span's name: the method and, when a route matched, its template. */
    public String spanName() {
        String name = "http " + method.toLowerCase(Locale.ROOT);
        return routePattern == null ? name : name + " " + routePattern;
    }

    /** The matched template; a 404 no route answered is the catch-all, as the static-resource mapping was. */
    private static String routePattern(HttpRequest<?> request, int status) {
        String pattern = request.getAttribute(HttpAttributes.URI_TEMPLATE, String.class).orElse(null);
        return pattern == null && status == 404 ? CATCH_ALL : pattern;
    }

    /** The template keeps cardinality bounded; requests no route matched collapse to a fixed word. */
    private static String uri(String pattern, int status) {
        if (pattern != null) {
            return pattern;
        }
        return status / 100 == 3 ? "REDIRECTION" : UNKNOWN;
    }

    private static String exceptionName(Throwable failure) {
        if (failure == null) {
            return NONE;
        }
        String simple = failure.getClass().getSimpleName();
        return simple.isEmpty() ? failure.getClass().getName() : simple;
    }
}
