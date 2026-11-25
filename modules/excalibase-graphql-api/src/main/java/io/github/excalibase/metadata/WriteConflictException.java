package io.github.excalibase.metadata;

/** The source holds a version other than the one the writer started from; nothing was written. */
public final class WriteConflictException extends Exception {

    private final String currentVersion;

    public WriteConflictException(String currentVersion) {
        super("the document changed since it was read", null, false, false);
        this.currentVersion = currentVersion;
    }

    /** What the source holds now, so the writer can reload and retry; {@value MetadataWriter#NO_DOCUMENT} when empty. */
    public String currentVersion() {
        return currentVersion;
    }
}
