package io.github.excalibase.config.ws;

import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.JwtVerificationException;
import io.github.excalibase.security.Principal;
import io.github.excalibase.security.RoleNotAllowedException;
import io.github.excalibase.security.RoleResolver;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Authenticates a WebSocket session: the token (from the upgrade request or the
 * {@code connection_init} payload), the project it must belong to, and the role the
 * session runs as through {@link RoleResolver}. The handshake interceptor and both
 * handlers go through here, so the rules are the HTTP filter's and exist once.
 */
final class WsSessionAuth {

    static final String PROJECT_MISMATCH = "project_mismatch";
    static final String MISSING_TOKEN = "missing_token";
    static final String SERVER_MISCONFIGURED = "server_misconfigured";

    /** The upgrade request's role header, kept until {@code connection_init} decides. */
    static final String SESSION_REQUESTED_ROLE_KEY = "excalibase.requestedRole";
    /** The upgrade request's {@code x-excalibase-*} headers. */
    static final String SESSION_HEADERS_KEY = "excalibase.sessionHeaders";

    private static final String BEARER = "Bearer ";
    private static final String ROLE_HEADER_KEY = RoleResolver.ROLE_HEADER.toLowerCase(Locale.ROOT);

    private WsSessionAuth() {
    }

    /** Why a session was refused: a message for the client plus a stable code. */
    static final class Failure extends Exception {
        private final String code;

        Failure(String message, String code) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /** At the HTTP upgrade: headers only. A missing token leaves the payload a chance to carry one. */
    static void onHandshake(JwtService jwtService, String authorization, Map<String, String> excalibaseHeaders,
                            String pathProject, Map<String, Object> attributes) throws Failure {
        String requestedRole = excalibaseHeaders.get(ROLE_HEADER_KEY);
        if (requestedRole != null) {
            attributes.put(SESSION_REQUESTED_ROLE_KEY, requestedRole);
        }
        attributes.put(SESSION_HEADERS_KEY, Map.copyOf(excalibaseHeaders));
        String token = bearer(authorization);
        JwtClaims claims = token == null ? null : verify(jwtService, token, pathProject);
        establish(claims, pathProject, requestedRole, excalibaseHeaders, attributes);
    }

    /**
     * At {@code connection_init}: claims verified at the upgrade stand; otherwise the payload's
     * token is verified. The payload's role (or its role header) overrides the upgrade's.
     */
    @SuppressWarnings("unchecked")
    static void onConnectionInit(JwtService jwtService, boolean tokenRequired, Map<String, Object> payload,
                                 Map<String, Object> attributes) throws Failure {
        String pathProject = (String) attributes.get(GraphQLWebSocketHandler.SESSION_PROJECT_KEY);
        Map<String, String> payloadHeaders = excalibaseHeaders(payload.get("headers"));
        JwtClaims claims = (JwtClaims) attributes.get(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY);
        if (claims == null) {
            String token = bearer(payloadAuthorization(payload));
            if (token != null) {
                claims = verify(jwtService, token, pathProject);
            } else if (tokenRequired) {
                throw new Failure("Missing Authorization token in connection_init payload", MISSING_TOKEN);
            }
        }
        Object payloadRole = payload.get("role");
        String requestedRole = payloadRole != null ? String.valueOf(payloadRole)
                : firstNonNull(payloadHeaders.get(ROLE_HEADER_KEY),
                        (String) attributes.get(SESSION_REQUESTED_ROLE_KEY));
        Map<String, String> sessionHeaders = new LinkedHashMap<>();
        Object handshakeHeaders = attributes.get(SESSION_HEADERS_KEY);
        if (handshakeHeaders instanceof Map<?, ?> headers) {
            sessionHeaders.putAll((Map<String, String>) headers);
        }
        sessionHeaders.putAll(payloadHeaders);
        establish(claims, pathProject, requestedRole, sessionHeaders, attributes);
    }

    private static void establish(JwtClaims claims, String pathProject, String requestedRole,
                                  Map<String, String> headers, Map<String, Object> attributes) throws Failure {
        String tenantId = claims != null ? claims.projectId() : null;
        if (claims != null && tenantId == null) {
            throw new Failure("JWT missing projectId claim", JwtVerificationException.INVALID_TOKEN);
        }
        if (tenantId != null && pathProject != null && !pathProject.equals(tenantId)) {
            // A token cannot reach another project's stream (mirrors the HTTP 403).
            throw new Failure("Token project does not match the request path", PROJECT_MISMATCH);
        }
        Principal principal;
        try {
            principal = RoleResolver.resolve(claims, requestedRole, headers);
        } catch (RoleNotAllowedException e) {
            throw new Failure("Role not allowed: " + e.code(), e.code());
        }
        if (claims != null) {
            attributes.put(GraphQLWebSocketHandler.SESSION_TENANT_KEY, tenantId);
            attributes.put(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY, claims);
        }
        attributes.put(GraphQLWebSocketHandler.SESSION_PRINCIPAL_KEY, principal);
    }

    private static JwtClaims verify(JwtService jwtService, String token, String pathProject) throws Failure {
        if (jwtService == null) {
            throw new Failure("Server misconfigured: JWT enabled but no verifier", SERVER_MISCONFIGURED);
        }
        try {
            return jwtService.verify(token, pathProject);
        } catch (JwtVerificationException e) {
            throw new Failure("Invalid or expired token: " + e.code(), e.code());
        }
    }

    /** Accepts both {@code payload.Authorization} and {@code payload.headers.Authorization}. */
    private static Object payloadAuthorization(Map<String, Object> payload) {
        Object direct = payload.get("Authorization");
        if (direct == null && payload.get("headers") instanceof Map<?, ?> headers) {
            return headers.get("Authorization");
        }
        return direct;
    }

    private static String bearer(Object authorization) {
        if (!(authorization instanceof String header) || !header.startsWith(BEARER)) {
            return null;
        }
        return header.substring(BEARER.length()).trim();
    }

    /** The {@code x-excalibase-*} entries of a header map, names lower-cased. */
    static Map<String, String> excalibaseHeaders(Object headers) {
        Map<String, String> selected = new LinkedHashMap<>();
        if (headers instanceof Map<?, ?> headerMap) {
            headerMap.forEach((name, value) -> {
                String lowerName = String.valueOf(name).toLowerCase(Locale.ROOT);
                if (lowerName.startsWith(Principal.SESSION_VARIABLE_PREFIX) && value instanceof String text) {
                    selected.put(lowerName, text);
                }
            });
        }
        return selected;
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
