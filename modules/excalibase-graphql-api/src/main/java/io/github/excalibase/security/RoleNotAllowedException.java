package io.github.excalibase.security;

/**
 * Thrown when a JWT carries a role claim (or scope) that is not permitted under
 * the configured Postgres role-switching policy. Mapped to HTTP 403 by
 * {@link PostgresRoleSwitchingAdvice}.
 */
public class RoleNotAllowedException extends RuntimeException {

    public RoleNotAllowedException(String message) {
        super(message);
    }
}
