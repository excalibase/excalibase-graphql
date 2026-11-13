package io.github.excalibase.compiler;

/**
 * A query refused before any SQL is built because its shape is abusive, not merely wrong: a fragment
 * cycle, or more selections than one request may expand to. The API answers it with a 400.
 */
public class QueryRejectedException extends IllegalArgumentException {

    public QueryRejectedException(String message) {
        super(message);
    }
}
