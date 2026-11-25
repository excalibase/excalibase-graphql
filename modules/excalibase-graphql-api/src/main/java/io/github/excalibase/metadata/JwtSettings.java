package io.github.excalibase.metadata;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The document's {@code jwt:} section, validated. Exactly one key source is set: {@code jwksUrl} (with
 * {@code issuer}), {@code issuer} alone for OIDC discovery, or {@code secretRef}, a reference to a shared
 * secret and never the secret.
 *
 * @param algorithms the only algorithms accepted, whatever the token header says
 * @param audience   the token's {@code aud} must contain one of these
 */
public record JwtSettings(String jwksUrl, String issuer, String secretRef, List<String> algorithms,
                          List<String> audience, Claims claims, Duration clockSkew) {

    /**
     * Where the caller is found in the token.
     *
     * @param role        path of the role: a string, or a list of the roles the caller may act as
     * @param defaultRole the active role when {@code role} is a list; null otherwise
     * @param userId      path of the caller's id
     * @param extra       session-variable name to the path of the claim that fills it
     */
    public record Claims(String role, String defaultRole, String userId, Map<String, String> extra) {
    }
}
