package io.github.excalibase.security;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;

/** A GraphQL-shaped error carrying a stable machine-readable code in {@code extensions.code}. */
public final class FilterErrors {

    /** The charset the servlet stack named for these answers. */
    private static final String CONTENT_TYPE = "application/json;charset=ISO-8859-1";

    private FilterErrors() {
    }

    public static HttpResponse<String> write(HttpStatus status, String message, String code) {
        return HttpResponse.<String>status(status).contentType(CONTENT_TYPE)
                .body("{\"errors\":[{\"message\":\"" + escape(message)
                        + "\",\"extensions\":{\"code\":\"" + escape(code) + "\"}}]}");
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
