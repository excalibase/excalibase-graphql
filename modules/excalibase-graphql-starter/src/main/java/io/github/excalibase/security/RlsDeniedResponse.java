package io.github.excalibase.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wire shapes for an RLS denial, shared by the GraphQL and REST surfaces so
 * both expose the same {@code RLS_DENIED} contract from one place.
 */
public final class RlsDeniedResponse {

    private static final String KEY_CODE = "code";
    private static final String KEY_MESSAGE = "message";

    private RlsDeniedResponse() {
    }

    /** GraphQL {@code errors[]} entry: short message plus typed {@code extensions}. */
    public static Map<String, Object> graphqlError(RlsViolationException denied) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put(KEY_CODE, denied.code());
        extensions.putAll(denied.details());

        Map<String, Object> error = new LinkedHashMap<>();
        error.put(KEY_MESSAGE, denied.getMessage());
        error.put("extensions", Collections.unmodifiableMap(extensions));
        return Collections.unmodifiableMap(error);
    }

    /**
     * REST body in the PostgREST {@code code}/{@code message}/{@code details}
     * shape. {@code error} is kept so existing clients reading the legacy
     * {@code { "error": ... }} envelope keep working.
     */
    public static Map<String, Object> restBody(RlsViolationException denied) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(KEY_CODE, denied.code());
        body.put(KEY_MESSAGE, denied.getMessage());
        body.put("details", denied.details());
        body.put("error", denied.getMessage());
        return Collections.unmodifiableMap(body);
    }
}
