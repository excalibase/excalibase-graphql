package io.github.excalibase.http;

import java.util.Arrays;
import java.util.Locale;
import java.util.List;

/**
 * Which request paths the servlet stack refused before any handler ran: Tomcat for an encoded slash,
 * backslash or NUL, then Spring Security's strict firewall for matrix parameters, dot segments, double
 * slashes and encoded dots, semicolons or percent signs. The query is not part of the path.
 */
final class RequestTargetRules {

    private static final List<String> REFUSED_BY_SERVER = List.of("%2f", "%5c", "%00");
    private static final List<String> REFUSED_BY_FIREWALL = List.of("%2e", "%3b", "%25");
    private static final List<String> DOT_SEGMENTS = List.of(".", "..");

    private RequestTargetRules() {
    }

    /** True for a path the server's own parser refuses; the answer is its HTML error page. */
    static boolean refusedByServer(String rawPath) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        return REFUSED_BY_SERVER.stream().anyMatch(lower::contains);
    }

    /** True for a path the application firewall refuses; the answer is a 400 with the default error body. */
    static boolean refusedByFirewall(String rawPath) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        return rawPath.indexOf(';') >= 0 || rawPath.indexOf('\\') >= 0 || rawPath.contains("//")
                || REFUSED_BY_FIREWALL.stream().anyMatch(lower::contains)
                || Arrays.stream(rawPath.split("/")).anyMatch(DOT_SEGMENTS::contains);
    }
}
