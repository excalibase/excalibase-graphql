package io.github.excalibase.security;

import java.util.Locale;

/**
 * Refuses the placeholder secret the examples used to carry. It passes the length check and is public, so anyone
 * could sign a token for an install that kept it.
 */
public final class PublicSecret {

    private static final String PLACEHOLDER = "change-me";

    private PublicSecret() {
    }

    /** @throws Refused when the secret holds the example placeholder, in any case */
    public static void refuse(String secret) {
        if (secret != null && secret.toLowerCase(Locale.ROOT).contains(PLACEHOLDER)) {
            throw new Refused();
        }
    }

    /** The token secret is the public example placeholder; distinct so a caller can keep its message. */
    public static final class Refused extends IllegalStateException {
        private Refused() {
            super("the token secret contains '" + PLACEHOLDER + "', the public placeholder from the examples; "
                    + "set a random one, for example: openssl rand -base64 48");
        }
    }
}
