package io.github.excalibase.permissions;

/**
 * A project's permissions could not be read and no earlier copy is cached. Requests are refused with
 * 503 {@value #CODE}; serving them without permissions is never an option.
 */
public class PermissionsUnavailableException extends RuntimeException {

    public static final String CODE = "permissions_unavailable";

    public PermissionsUnavailableException(String message) {
        super(message);
    }

    public PermissionsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
