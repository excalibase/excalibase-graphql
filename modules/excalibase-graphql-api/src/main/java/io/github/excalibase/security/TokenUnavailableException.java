package io.github.excalibase.security;

/**
 * No provisioning token could be resolved — neither from the mounted token file
 * nor from a configured literal. Callers must surface this rather than send an
 * empty bearer, which the control plane answers with a 401 that reads like a
 * revoked token instead of a misconfiguration.
 */
public class TokenUnavailableException extends RuntimeException {

    public TokenUnavailableException(String message) {
        super(message);
    }
}
