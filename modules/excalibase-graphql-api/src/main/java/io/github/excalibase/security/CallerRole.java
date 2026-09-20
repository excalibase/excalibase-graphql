package io.github.excalibase.security;

/**
 * The role exposure grants are evaluated against.
 *
 * <p>A grant may name exactly two roles: {@link #ANON} for a caller who is not
 * signed in and {@link #AUTHENTICATED} for one who is. That vocabulary is fixed
 * — the control plane refuses any other value — so the role is <em>derived</em>
 * from the presence of verified claims, never read off the token.
 *
 * <p>Deliberately not the {@code role} claim. That claim is free-form input for
 * row-level policies, defaults to {@code "user"} when a token omits it, and so
 * would match neither grant role: taking exposure's role from it leaves every
 * grant inert.
 */
public final class CallerRole {

    /** A caller who presented no token. */
    public static final String ANON = "anon";

    /** A caller whose token verified, whatever its {@code role} claim says. */
    public static final String AUTHENTICATED = "authenticated";

    private CallerRole() {
    }

    public static String of(JwtClaims claims) {
        return claims == null ? ANON : AUTHENTICATED;
    }
}
