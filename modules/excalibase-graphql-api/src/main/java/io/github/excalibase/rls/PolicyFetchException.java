package io.github.excalibase.rls;

/**
 * Thrown when policies cannot be fetched from provisioning and nothing is
 * cached. Fatal by design: failing the request keeps RLS fail-closed rather
 * than letting an empty (UNRESTRICTED) policy set leak data.
 */
public class PolicyFetchException extends RuntimeException {

    public PolicyFetchException(String message) {
        super(message);
    }

    public PolicyFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
