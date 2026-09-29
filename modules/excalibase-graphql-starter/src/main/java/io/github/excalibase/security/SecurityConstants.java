package io.github.excalibase.security;

public final class SecurityConstants {
    public static final String JWT_CLAIMS_ATTR = "jwt.claims";
    /** The {@link Principal} the request runs as, set by the authentication filter. */
    public static final String PRINCIPAL_ATTR = "excalibase.principal";
    private SecurityConstants() {}
}
