package io.github.excalibase.exception;

public class DataFetcherException extends RuntimeException{
    public DataFetcherException(String message) {
        super(message);
    }

    public DataFetcherException(String message, Throwable cause) {
        super(message, cause);
    }
}
