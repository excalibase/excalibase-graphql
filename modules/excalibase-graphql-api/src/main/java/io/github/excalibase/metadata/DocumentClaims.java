package io.github.excalibase.metadata;

import com.nimbusds.jwt.JWTClaimsSet;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtVerificationException;
import io.github.excalibase.security.Principal;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a verified token into the engine's {@link JwtClaims} using the document's claim paths. Only what the
 * document maps becomes a session variable: the caller's id, the email address and the named extras. Other
 * claims of the token are not exposed, so a provider's claim cannot pose as a variable no one mapped.
 */
final class DocumentClaims {

    private final JwtSettings.Claims paths;

    DocumentClaims(JwtSettings.Claims paths) {
        this.paths = paths;
    }

    JwtClaims map(JWTClaimsSet verified, String projectId) {
        Map<String, Object> raw = verified.getClaims();
        String userId = scalar(ClaimPath.resolve(raw, paths.userId()));
        if (userId == null) {
            throw new JwtVerificationException("Missing required claim: " + paths.userId());
        }
        Set<String> allowed = new LinkedHashSet<>();
        String role = role(ClaimPath.resolve(raw, paths.role()), allowed);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("userId", userId);
        paths.extra().forEach((name, path) -> {
            Object value = ClaimPath.resolve(raw, path);
            if (isScalarOrList(value)) {
                variables.put(name, value);
            }
        });
        String email = raw.get("email") instanceof String address ? address : null;
        return new JwtClaims(userId, projectId, null, null, "", role, email, "authenticated", 0L, variables, allowed);
    }

    /** A string is the one role; a list is the roles the caller may act as, and the document's default is active. */
    private String role(Object claim, Set<String> allowed) {
        if (claim instanceof String single) {
            requireUsable(single);
            allowed.add(single);
            return single;
        }
        if (claim instanceof List<?> listed) {
            listed.stream().filter(String.class::isInstance).map(String.class::cast)
                    .filter(DocumentClaims::usable).forEach(allowed::add);
            if (paths.defaultRole() != null && allowed.contains(paths.defaultRole())) {
                return paths.defaultRole();
            }
            throw invalidRole("JWT role list does not contain the document's default role");
        }
        throw invalidRole("JWT role claim is missing or not a role name or a list of them");
    }

    private static void requireUsable(String role) {
        if (!usable(role)) {
            throw invalidRole("JWT role claim is not a valid role name");
        }
    }

    /** Never {@code service}: the bypass role is for the engine's own tokens, not an outside provider's. */
    private static boolean usable(String role) {
        return Principal.isValidRoleName(role) && !Principal.SERVICE.equals(role);
    }

    private static boolean isScalarOrList(Object value) {
        return value instanceof List<?> list ? list.stream().allMatch(DocumentClaims::isScalar) : isScalar(value);
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static String scalar(Object value) {
        return isScalar(value) && !String.valueOf(value).isBlank() ? String.valueOf(value) : null;
    }

    private static JwtVerificationException invalidRole(String message) {
        return new JwtVerificationException(JwtVerificationException.INVALID_ROLE_CLAIM, message);
    }
}
