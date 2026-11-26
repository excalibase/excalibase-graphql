package io.github.excalibase.schema;

/** A surface (REST, realtime, functions) the project's database does not have yet; the request is refused, not guessed. */
public class SurfaceNotServedException extends RuntimeException {

    public static final String CODE = "surface_not_served";

    public SurfaceNotServedException(String message) {
        super(message);
    }
}
