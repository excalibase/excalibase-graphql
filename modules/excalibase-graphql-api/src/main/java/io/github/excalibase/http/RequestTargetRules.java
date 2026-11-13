package io.github.excalibase.http;

import java.util.Arrays;
import java.util.Locale;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Which request paths the servlet stack refused before any handler ran: Tomcat for an encoded slash,
 * backslash or NUL, then Spring Security's strict firewall for matrix parameters, dot segments, double
 * slashes, encoded dots, semicolons, percent signs, line feeds, carriage returns and line or paragraph
 * separators. The query is not part of the path.
 */
final class RequestTargetRules {

    private static final List<String> REFUSED_BY_SERVER = List.of("%2f", "%5c", "%00");
    private static final String LINE_SEPARATOR = "\u2028";
    private static final String PARAGRAPH_SEPARATOR = "\u2029";
    /** The same two characters as the request line's bytes, which the HTTP codec reads one byte to a char. */
    private static final String LINE_SEPARATOR_BYTES = "\u00e2\u0080\u00a8";
    private static final String PARAGRAPH_SEPARATOR_BYTES = "\u00e2\u0080\u00a9";
    private static final List<String> REFUSED_BY_FIREWALL = List.of("%2e", "%3b", "%25", "%0a", "%0d", "%e2%80%a8", "%e2%80%a9");
    private static final List<String> DOT_SEGMENTS = List.of(".", "..");

    private static final Set<String> SERVED_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
    private static final String TRACE_ALLOW = "HEAD, DELETE, POST, GET, OPTIONS, PUT";

    private RequestTargetRules() {
    }

    /** True for a path the server's own parser refuses; the answer is its HTML error page. */
    static boolean refusedByServer(String rawPath) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        return REFUSED_BY_SERVER.stream().anyMatch(lower::contains)
                || Stream.of(LINE_SEPARATOR, PARAGRAPH_SEPARATOR, LINE_SEPARATOR_BYTES, PARAGRAPH_SEPARATOR_BYTES)
                        .anyMatch(rawPath::contains);
    }

    /** True for a path the application firewall refuses; the answer is a 400 with the default error body. */
    static boolean refusedByFirewall(String rawPath) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        return rawPath.indexOf(';') >= 0 || rawPath.indexOf('\\') >= 0 || rawPath.contains("//")
                || REFUSED_BY_FIREWALL.stream().anyMatch(lower::contains)
                || Arrays.stream(rawPath.split("/")).anyMatch(DOT_SEGMENTS::contains);
    }

    /**
     * The answer for a method the stack served none of: TRACE names the methods it does serve, any other
     * unknown one gets a bare 400. Null for a method that goes on to routing.
     */
    static HttpResponse<?> refusalForMethod(String method) {
        if (SERVED_METHODS.contains(method)) {
            return null;
        }
        MutableHttpResponse<Object> refusal = HttpResponse.badRequest();
        return "TRACE".equals(method) ? refusal.header("Allow", TRACE_ALLOW) : refusal;
    }
}
