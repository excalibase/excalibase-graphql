package io.github.excalibase.security;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The one role a request runs as (docs/features/permissions.md §1), plus what the
 * request knows about its caller. {@code bypass} is true exactly when the role is
 * {@link #SERVICE}: the constructor refuses any other combination, so the bypass can
 * never be carried by a role that permissions are supposed to govern.
 *
 * @param claims               the verified token, or null for a request without one
 * @param impersonationHeaders {@code x-excalibase-*} request headers, non-empty only when a
 *                             service token acts as another role (names lower-cased)
 */
public record Principal(String role, boolean bypass, JwtClaims claims, Map<String, String> impersonationHeaders) {

    public static final String ANON = "anon";
    public static final String SERVICE = "service";

    /** Prefix every session variable carries, compared case-insensitively. */
    public static final String SESSION_VARIABLE_PREFIX = "x-excalibase-";

    private static final String EMAIL_CLAIM = "email";

    private static final Pattern ROLE_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    /** Registered JWT claims describe the token, not the caller, so they never become variables. */
    private static final Set<String> NON_SESSION_CLAIMS = Set.of(
            "iss", "aud", "exp", "iat", "nbf", "jti", "token_use");

    /** Claims surfaced under their own variable names (or not at all, for role and projectId). */
    private static final Set<String> EXPLICIT_CLAIMS = Set.of("role", "userid", EMAIL_CLAIM, "projectid");

    private static final Set<String> API_KEY_SCOPES = Set.of(SERVICE, "public", "api-key");

    public Principal {
        if (!isValidRoleName(role)) {
            throw new IllegalArgumentException("Invalid role name");
        }
        if (bypass != SERVICE.equals(role)) {
            throw new IllegalArgumentException("Only the service role bypasses permissions");
        }
        impersonationHeaders = lowerCaseNames(impersonationHeaders);
    }

    public static Principal anonymous() {
        return new Principal(ANON, false, null, Map.of());
    }

    public static boolean isValidRoleName(String role) {
        return role != null && ROLE_NAME.matcher(role).matches();
    }

    /** A service token acting as another role: its own claims then say nothing about the caller. */
    public boolean impersonating() {
        return claims != null && SERVICE.equals(claims.role()) && !bypass;
    }

    /**
     * Session variables per spec §2, keyed by lower-cased name. While impersonating, the
     * caller is described only by the request's {@code x-excalibase-*} headers.
     */
    public Map<String, String> sessionVariables(String projectId) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (impersonating()) {
            variables.putAll(impersonationHeaders);
        } else if (claims != null) {
            variables.putAll(claimVariables(claims));
        }
        if (projectId != null) {
            variables.put(SESSION_VARIABLE_PREFIX + "project-id", projectId);
        }
        variables.put(SESSION_VARIABLE_PREFIX + "role", role);
        return Map.copyOf(variables);
    }

    private static Map<String, String> claimVariables(JwtClaims claims) {
        Map<String, Object> raw = claims.extraClaims();
        Map<String, String> variables = new LinkedHashMap<>();
        raw.forEach((name, value) -> {
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (value != null && !NON_SESSION_CLAIMS.contains(lowerName) && !EXPLICIT_CLAIMS.contains(lowerName)) {
                variables.put(SESSION_VARIABLE_PREFIX + lowerName, render(value));
            }
        });
        if (raw.get("userId") != null) {
            variables.put(SESSION_VARIABLE_PREFIX + "user-id", render(raw.get("userId")));
        }
        String email = emailOf(claims);
        if (email != null) {
            variables.put(SESSION_VARIABLE_PREFIX + EMAIL_CLAIM, email);
        }
        return variables;
    }

    private static String emailOf(JwtClaims claims) {
        Map<String, Object> raw = claims.extraClaims();
        if (raw.get(EMAIL_CLAIM) instanceof String email) {
            return email;
        }
        boolean apiKeyToken = raw.containsKey("keyId")
                || (claims.scope() != null && API_KEY_SCOPES.contains(claims.scope()));
        return !apiKeyToken && raw.get("sub") instanceof String subject ? subject : null;
    }

    /** Scalars as text; arrays as a Postgres array literal with every element quoted. */
    private static String render(Object value) {
        if (value instanceof Collection<?> elements) {
            return elements.stream()
                    .map(element -> "\"" + String.valueOf(element).replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                    .collect(Collectors.joining(",", "{", "}"));
        }
        return String.valueOf(value);
    }

    private static Map<String, String> lowerCaseNames(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> lowered = new LinkedHashMap<>();
        headers.forEach((name, value) ->
                lowered.put(Objects.requireNonNull(name).toLowerCase(Locale.ROOT), Objects.requireNonNull(value)));
        return Map.copyOf(lowered);
    }
}
