package io.github.excalibase.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The wire shape of a permission error, shared by the GraphQL and REST surfaces: a stable machine
 * code plus a short message that names nothing about rows or SQL.
 */
public final class PermissionErrors {

    /** A method whose operation the role holds no permission for, on a table it can see. */
    public static final String PERMISSION_DENIED = "permission_denied";

    private static final String KEY_CODE = "code";
    private static final String KEY_MESSAGE = "message";

    private PermissionErrors() {
    }

    /** GraphQL {@code errors[]} entry with {@code extensions.code}. */
    public static Map<String, Object> graphqlError(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put(KEY_MESSAGE, message);
        error.put("extensions", Map.of(KEY_CODE, code));
        return Collections.unmodifiableMap(error);
    }

    /** REST body: {@code code} and {@code message}, plus the {@code error} key REST errors always carry. */
    public static Map<String, Object> restBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(KEY_CODE, code);
        body.put(KEY_MESSAGE, message);
        body.put("error", message);
        return Collections.unmodifiableMap(body);
    }
}
