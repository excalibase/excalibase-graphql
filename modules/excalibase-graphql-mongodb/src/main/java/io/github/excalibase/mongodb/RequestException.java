package io.github.excalibase.mongodb;

/** A request the engine refuses; its message names the caller's own mistake and is safe to show. */
public class RequestException extends RuntimeException {
    public RequestException(String message) {
        super(message);
    }
}
