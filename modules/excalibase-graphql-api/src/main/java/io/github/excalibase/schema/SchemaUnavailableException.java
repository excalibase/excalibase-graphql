package io.github.excalibase.schema;

/**
 * A project's database could not be reflected, as while a restore replaces it. The request is refused
 * with 503 and nothing is cached, so the next request reflects the database again.
 */
public class SchemaUnavailableException extends RuntimeException {

    public SchemaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
