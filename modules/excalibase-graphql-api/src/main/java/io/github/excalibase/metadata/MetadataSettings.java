package io.github.excalibase.metadata;

/**
 * The configuration that picks and parameterises the metadata source.
 *
 * @param source  {@code metadata.source}; blank keeps the deployment's previous permission source
 * @param file    {@code metadata.file.*}
 * @param url     {@code metadata.url.*}
 */
public record MetadataSettings(String source, String policyUrl, String projectId, String reservedSchemas,
                               FileSettings file, UrlSettings url) {

    /** @param pollIntervalMs how often the file's content hash is checked */
    public record FileSettings(String path, long pollIntervalMs) {
    }
}
