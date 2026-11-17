package io.github.excalibase.metadata;

/** A save started from a version that is no longer the current one; someone else saved first. */
public class MetadataConflictException extends RuntimeException {

    public MetadataConflictException(String message) {
        super(message);
    }
}
