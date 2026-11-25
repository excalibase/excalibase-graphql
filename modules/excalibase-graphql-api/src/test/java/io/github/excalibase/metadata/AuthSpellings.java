package io.github.excalibase.metadata;

import java.util.List;

/** Property spellings shared by the allow-list test and the test that proves what Micronaut reads. */
public final class AuthSpellings {

    /**
     * Micronaut reads these under an engine name (checked by starting an environment with the engine's
     * application.yml): any case, camel case and {@code _} for {@code -}.
     */
    public static final List<String> READ = List.of("issuer", "Issuer", "jwks-url", "jwksUrl", "JWKS-URL",
            "jwks_url", "secret", "secret-file", "secretFile", "secret_file", "algorithms", "audience", "clock-skew",
            "clockSkew", "clock_skew", "claims.role", "Claims.Role", "CLAIMS.ROLE",
            "claims.default-role", "claims.defaultRole", "claims.default_role",
            "claims.user-id", "claims.userId", "claims.user_id", "claims.extra");

    /**
     * Micronaut never reads these under any engine name: accepting one would start cleanly and silently leave a
     * default in place. A dash or underscore cannot stand in for the dot between {@code claims} and its field, and a dot does not stand in for a dash.
     */
    public static final List<String> NEVER_READ = List.of("claimsRole", "claims-role", "claims_role", "clockskew",
            "claims-user-id", "claimsuserid", "claim.role", "claims.extra.x", "claims.role.x", "issuer.x",
            "claimsdefaultrole", "jwks.url", "clock.skew", "claims.default.role", "claims.user.id");

    private AuthSpellings() {
    }
}
