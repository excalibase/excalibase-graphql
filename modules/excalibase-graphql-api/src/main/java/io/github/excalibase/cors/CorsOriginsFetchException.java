package io.github.excalibase.cors;

/** A project's origin allowlist could not be fetched and nothing is cached for it. */
public class CorsOriginsFetchException extends RuntimeException {

    public CorsOriginsFetchException(String message) {
        super(message);
    }

    public CorsOriginsFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
