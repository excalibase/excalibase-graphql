package io.github.excalibase.security;

/**
 * Thrown when a mutation's candidate row fails the RLS WITH-CHECK — the caller
 * is attempting to write a row no policy permits them to. Unchecked so it
 * propagates out of the compiler; the GraphQL/REST error handlers surface it as
 * a request error without exposing internals.
 */
public class RlsViolationException extends RuntimeException {
    public RlsViolationException(String message) {
        super(message);
    }
}
