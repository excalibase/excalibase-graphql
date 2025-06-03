package io.github.excalibase.exception;

/**
 * Exception thrown when data fetching operations fail.
 */
public class DataFetcherException extends RuntimeException{
    public DataFetcherException(String message) {
        super(message);
    }

    public DataFetcherException(String message, Throwable cause) {
        super(message, cause);
    }
}
