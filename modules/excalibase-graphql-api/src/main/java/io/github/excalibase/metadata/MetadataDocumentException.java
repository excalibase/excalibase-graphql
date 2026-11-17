package io.github.excalibase.metadata;

/** A metadata document that cannot be loaded: the whole document is refused, never partly applied. */
public class MetadataDocumentException extends RuntimeException {

    public MetadataDocumentException(String message) {
        super(message);
    }

    public MetadataDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
