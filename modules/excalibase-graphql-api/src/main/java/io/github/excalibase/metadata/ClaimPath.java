package io.github.excalibase.metadata;

import java.util.Map;

/**
 * Finds a value inside a token's claims by a dotted path such as {@code realm_access.roles}. Identity providers
 * name claims with URLs ({@code https://hasura.io/jwt/claims}), whose dots are part of the key, so at every level
 * the whole remaining path is tried as a key first and then each split at a dot, left to right.
 */
public final class ClaimPath {

    private ClaimPath() {
    }

    /** @return the value, or null when any step is missing */
    public static Object resolve(Map<String, Object> claims, String path) {
        return lookup(claims, path);
    }

    private static Object lookup(Object node, String path) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        if (map.containsKey(path)) {
            return map.get(path);
        }
        int dot = path.indexOf('.');
        while (dot >= 0) {
            Object child = map.get(path.substring(0, dot));
            Object found = child == null ? null : lookup(child, path.substring(dot + 1));
            if (found != null) {
                return found;
            }
            dot = path.indexOf('.', dot + 1);
        }
        return null;
    }
}
