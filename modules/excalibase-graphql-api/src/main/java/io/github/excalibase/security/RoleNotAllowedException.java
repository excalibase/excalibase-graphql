package io.github.excalibase.security;

/**
 * The request asked to run as a role its credentials do not allow (spec §1.1).
 * Answered with 403 and {@link #ROLE_NOT_ALLOWED}; the message never names the role.
 */
public class RoleNotAllowedException extends RuntimeException {

    public static final String ROLE_NOT_ALLOWED = "role_not_allowed";

    public RoleNotAllowedException(String message) {
        super(message);
    }

    public String code() {
        return ROLE_NOT_ALLOWED;
    }
}
