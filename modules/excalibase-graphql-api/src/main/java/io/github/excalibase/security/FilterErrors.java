package io.github.excalibase.security;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;

/** A GraphQL-shaped error carrying a stable machine-readable code in {@code extensions.code}. */
public final class FilterErrors {

    private FilterErrors() {
    }

    public static HttpResponse<String> write(HttpStatus status, String message, String code) {
        return HttpResponse.<String>status(status).contentType(MediaType.APPLICATION_JSON)
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
