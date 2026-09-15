package io.github.excalibase.security;

/**
 * Thrown when a bearer token cannot be trusted.
 *
 * <p>Carries a stable machine-readable {@link #code()} alongside the human
 * message so a client can tell an audience failure from an ordinary expiry
 * without parsing prose. Codes follow the same convention as
 * {@code RlsViolationException.CODE}: a constant per condition, not an enum.
 */
public class JwtVerificationException extends RuntimeException {

    /** Default code for any verification failure with no more specific cause. */
    public static final String INVALID_TOKEN = "invalid_token";

    /** The token's {@code aud} does not cover the project being addressed. */
    public static final String AUD_MISMATCH = "aud_mismatch";

    /** A refresh credential was presented where an access token is required. */
    public static final String REFRESH_TOKEN_NOT_ACCEPTED = "refresh_token_not_accepted";

    private final String code;

    public JwtVerificationException(String message) {
        this(INVALID_TOKEN, message);
    }

    public JwtVerificationException(String message, Throwable cause) {
        this(INVALID_TOKEN, message, cause);
    }

    public JwtVerificationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public JwtVerificationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
