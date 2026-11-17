package io.github.excalibase.metadata;

/** The text a source read and the version it calls it; the common loop parses and validates it. */
public record RawMetadata(String text, String version) {
}
