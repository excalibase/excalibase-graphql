package io.github.excalibase.exception;

public class DataMutationException extends RuntimeException {
    public DataMutationException(String message) {
        super(message);
    }

    public DataMutationException(String message, Throwable cause) {
        super(message, cause);
    }
}
