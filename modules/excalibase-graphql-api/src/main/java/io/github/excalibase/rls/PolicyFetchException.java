package io.github.excalibase.rls;

/**
 * Thrown when the engine cannot obtain a project's policies from the
 * provisioning service and has no cached copy to fall back on.
 *
 * <p>This is deliberately fatal to the request: returning an empty policy
 * list would be read by the engine as {@code UNRESTRICTED} (no row/column
 * filtering), which on a fetch failure would silently disable RLS and expose
 * data. Failing the request instead keeps the system fail-closed.
 */
public class PolicyFetchException extends RuntimeException {

    public PolicyFetchException(String message) {
        super(message);
    }

    public PolicyFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
