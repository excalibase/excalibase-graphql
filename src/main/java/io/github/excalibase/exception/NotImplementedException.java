package io.github.excalibase.exception;

/**
 * Exception thrown for features that are not yet implemented.
 */
public class NotImplementedException extends RuntimeException {
    public NotImplementedException(String message) {
        super(message);
    }
}
