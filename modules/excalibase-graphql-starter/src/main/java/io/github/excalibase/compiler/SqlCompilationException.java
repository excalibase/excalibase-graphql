package io.github.excalibase.compiler;

/** Thrown when GraphQL-to-SQL compilation fails (parsing, schema lookup, invalid arguments). */
public class SqlCompilationException extends RuntimeException {
    public SqlCompilationException(String message, Throwable cause) {
        super(message, cause);
    }

    public SqlCompilationException(String message) {
        super(message);
    }
}
