package io.github.excalibase.metadata;

/**
 * The configuration that picks and parameterises the metadata source.
 *
 * @param source  {@code metadata.source}; blank keeps the deployment's previous permission source
 * @param file    {@code metadata.file.*}
 * @param url     {@code metadata.url.*}
 * @param envJwtConfigured the engine has {@code app.security.auth} token settings, so a document may not add its own
 */
public record MetadataSettings(String source, String policyUrl, String projectId, String reservedSchemas,
                               FileSettings file, UrlSettings url, boolean envJwtConfigured) {

    /** @param pollIntervalMs how often the file's content hash is checked */
    public record FileSettings(String path, long pollIntervalMs) {
    }
}
