package io.github.excalibase.security;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Decides the one role a request runs as (docs/features/permissions.md §1.1). Every
 * entry point — the HTTP filter, the WebSocket handshake and both {@code connection_init}
 * handlers — resolves through {@link #resolve}, so the rules exist once.
 */
public final class RoleResolver {

    /** Request header (and WebSocket payload header) that picks one of the allowed roles. */
    public static final String ROLE_HEADER = "X-Excalibase-Role";

    private static final String ROLE_HEADER_KEY = ROLE_HEADER.toLowerCase(Locale.ROOT);

    private RoleResolver() {
    }

    /**
     * @param claims             the verified token, or null when the request carries none
     * @param requestedRole      the {@link #ROLE_HEADER} value, or null when absent
     * @param excalibaseHeaders  the request's headers; only {@code x-excalibase-*} ones are kept,
     *                           and only when a service token acts as another role
     * @throws RoleNotAllowedException when the requested role is not one the caller may act as
     */
    public static Principal resolve(JwtClaims claims, String requestedRole, Map<String, String> excalibaseHeaders) {
        if (claims == null) {
            return resolveAnonymous(requestedRole);
        }
        if (isServiceToken(claims)) {
            return resolveService(claims, requestedRole, excalibaseHeaders);
        }
        String role = requestedRole == null ? claims.role() : requestedRole;
        if (!Principal.isValidRoleName(role) || Principal.SERVICE.equals(role)
                || !claims.allowedRoles().contains(role)) {
            throw new RoleNotAllowedException("The token may not act as the requested role");
        }
        return new Principal(role, false, claims, Map.of());
    }

    private static Principal resolveAnonymous(String requestedRole) {
        if (requestedRole != null && !Principal.ANON.equals(requestedRole)) {
            throw new RoleNotAllowedException("A request without a token runs as anon only");
        }
        return Principal.anonymous();
    }

    private static Principal resolveService(JwtClaims claims, String requestedRole,
                                            Map<String, String> excalibaseHeaders) {
        if (requestedRole == null || Principal.SERVICE.equals(requestedRole)) {
            return new Principal(Principal.SERVICE, true, claims, Map.of());
        }
        if (!Principal.isValidRoleName(requestedRole)) {
            throw new RoleNotAllowedException("The requested role is not a valid role name");
        }
        return new Principal(requestedRole, false, claims, sessionHeaders(excalibaseHeaders));
    }

    /** Role and scope must both be {@code service}; a service role without the scope is refused outright. */
    private static boolean isServiceToken(JwtClaims claims) {
        boolean serviceRole = Principal.SERVICE.equals(claims.role());
        if (serviceRole && !Principal.SERVICE.equals(claims.scope())) {
            throw new RoleNotAllowedException("The service role requires the service scope");
        }
        return serviceRole;
    }

    private static Map<String, String> sessionHeaders(Map<String, String> headers) {
        Map<String, String> sessionHeaders = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, value) -> {
                String lowerName = name.toLowerCase(Locale.ROOT);
                if (lowerName.startsWith(Principal.SESSION_VARIABLE_PREFIX) && !lowerName.equals(ROLE_HEADER_KEY)
                        && value != null) {
                    sessionHeaders.put(lowerName, value);
                }
            });
        }
        return Map.copyOf(sessionHeaders);
    }
}
