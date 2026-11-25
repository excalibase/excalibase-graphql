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

    /** A guest account's flag, surfaced as {@code x-excalibase-is-guest}. */
    private static final String IS_GUEST_CLAIM = "is_guest";

    /** Authenticator assurance level, surfaced as {@code x-excalibase-aal} only when it is a known level. */
    private static final String AAL_CLAIM = "aal";

    private static final Set<String> ASSURANCE_LEVELS = Set.of("aal1", "aal2");

    private static final Pattern ROLE_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    /** Registered JWT claims describe the token, not the caller, so they never become variables. */
    private static final Set<String> NON_SESSION_CLAIMS = Set.of(
            "iss", "aud", "exp", "iat", "nbf", "jti", "token_use");

    /** Claims surfaced under their own variable names (or not at all, for role and projectId). */
    private static final Set<String> EXPLICIT_CLAIMS = Set.of(
            "role", EMAIL_CLAIM, "projectid", IS_GUEST_CLAIM, AAL_CLAIM);

    /** Names that mean the end-user id once case and dash/underscore are ignored (EXC-879). */
    private static final Set<String> USER_ID_NAMES = Set.of("user", "userid", "user-id");

    public Principal {
        if (!isValidRoleName(role)) {
            throw new IllegalArgumentException("Invalid role name");
        }
        if (bypass != SERVICE.equals(role)) {
            throw new IllegalArgumentException("Only the service role bypasses permissions");
        }
        impersonationHeaders = lowerCaseNames(impersonationHeaders);
    }

    /**
     * True for any claim name that could be read as the end-user id ({@code user-id}, {@code USER_ID},
     * {@code userId}, {@code user}): the id comes only from {@code sub}, so no custom claim may use these.
     */
    public static boolean isUserIdName(String name) {
        return name != null && USER_ID_NAMES.contains(name.toLowerCase(Locale.ROOT).replace('_', '-'));
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
            // The id is the token's sub (JwtClaims.userId); anon has no user, even with a keyed subject.
            if (!ANON.equals(role) && claims.userId() != null && !claims.userId().isBlank()) {
                variables.put(SESSION_VARIABLE_PREFIX + "user-id", claims.userId());
            }
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
            if (value != null && !NON_SESSION_CLAIMS.contains(lowerName) && !EXPLICIT_CLAIMS.contains(lowerName)
                    && !isUserIdName(name)) {
                variables.put(SESSION_VARIABLE_PREFIX + lowerName, render(value));
            }
        });
        // The address is only ever the email claim: sub is the account id, never an address.
        if (raw.get(EMAIL_CLAIM) instanceof String email) {
            variables.put(SESSION_VARIABLE_PREFIX + EMAIL_CLAIM, email);
        }
        if (raw.get(IS_GUEST_CLAIM) instanceof Boolean guest) {
            variables.put(SESSION_VARIABLE_PREFIX + "is-guest", guest.toString());
        }
        if (raw.get(AAL_CLAIM) instanceof String level && ASSURANCE_LEVELS.contains(level)) {
            variables.put(SESSION_VARIABLE_PREFIX + AAL_CLAIM, level);
        }
        return variables;
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
