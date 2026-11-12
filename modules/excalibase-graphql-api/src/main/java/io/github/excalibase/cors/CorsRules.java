package io.github.excalibase.cors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One origin allowlist with the methods and headers it grants: the subset of CORS rules this API uses. */
public final class CorsRules {

    public static final String ALL = "*";

    private final List<String> allowedOrigins;
    private final List<String> allowedMethods;
    private final List<String> allowedHeaders;
    private final List<String> exposedHeaders;
    private final boolean allowCredentials;
    private final long maxAgeSeconds;

    public CorsRules(List<String> allowedOrigins, List<String> allowedMethods, List<String> allowedHeaders,
                     List<String> exposedHeaders, boolean allowCredentials, long maxAgeSeconds) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
        this.allowedMethods = List.copyOf(allowedMethods);
        this.allowedHeaders = List.copyOf(allowedHeaders);
        this.exposedHeaders = List.copyOf(exposedHeaders);
        this.allowCredentials = allowCredentials;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public List<String> allowedOrigins() {
        return allowedOrigins;
    }

    public List<String> allowedMethods() {
        return allowedMethods;
    }

    public List<String> exposedHeaders() {
        return exposedHeaders;
    }

    public boolean allowCredentials() {
        return allowCredentials;
    }

    public long maxAgeSeconds() {
        return maxAgeSeconds;
    }

    /** The value for Access-Control-Allow-Origin, or null when the origin is not allowed. */
    public String checkOrigin(String requestOrigin) {
        if (requestOrigin == null || requestOrigin.isBlank()) {
            return null;
        }
        if (allowedOrigins.contains(ALL)) {
            return allowCredentials ? requestOrigin : ALL;
        }
        String trimmed = requestOrigin.endsWith("/") ? requestOrigin.substring(0, requestOrigin.length() - 1) : requestOrigin;
        for (String allowed : allowedOrigins) {
            String candidate = allowed.endsWith("/") ? allowed.substring(0, allowed.length() - 1) : allowed;
            if (trimmed.equalsIgnoreCase(candidate)) {
                return requestOrigin;
            }
        }
        return null;
    }

    /** The methods to grant a preflight asking for {@code requestMethod}, or null when it is not allowed. */
    public List<String> checkMethod(String requestMethod) {
        if (requestMethod == null) {
            return null;
        }
        if (allowedMethods.contains(ALL) || allowedMethods.contains(requestMethod.toUpperCase(Locale.ROOT))) {
            return allowedMethods;
        }
        return null;
    }

    /** The headers to grant a preflight asking for {@code requested}, or null when one is not allowed. */
    public List<String> checkHeaders(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        if (allowedHeaders.isEmpty()) {
            return null;
        }
        boolean all = allowedHeaders.contains(ALL);
        List<String> granted = new ArrayList<>();
        for (String header : requested) {
            boolean listed = all || allowedHeaders.stream().anyMatch(header::equalsIgnoreCase);
            if (!listed) {
                return null;
            }
            granted.add(header);
        }
        return granted;
    }
}
