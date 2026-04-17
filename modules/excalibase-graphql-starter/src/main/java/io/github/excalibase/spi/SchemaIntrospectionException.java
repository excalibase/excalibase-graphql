package io.github.excalibase.spi;

/** Thrown when database schema introspection fails (column metadata, FK parsing, etc.). */
public class SchemaIntrospectionException extends RuntimeException {
    public SchemaIntrospectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
