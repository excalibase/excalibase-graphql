package io.github.excalibase.errors;

import java.util.Objects;

/** A request the engine refuses before it reaches the database, reported as {@link #error()}. */
public class DataErrorException extends RuntimeException {

    private final transient DataError error;

    public DataErrorException(DataError error) {
        super(Objects.requireNonNull(error, "error").message());
        this.error = error;
    }

    /** As above, with the failure that made the engine refuse, kept for the log. */
    public DataErrorException(DataError error, Throwable cause) {
        super(Objects.requireNonNull(error, "error").message(), cause);
        this.error = error;
    }

    public DataError error() {
        return error;
    }
}
