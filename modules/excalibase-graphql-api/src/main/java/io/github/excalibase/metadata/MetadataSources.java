package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Picks the metadata source from configuration. {@code metadata.source} has no default and no fallback chain:
 * unset means the deployment keeps today's permission source (provisioning, or none), and a source together with
 * {@code app.security.rls.policy-url} is refused rather than guessed between.
 */
public final class MetadataSources {

    private MetadataSources() {
    }

    /** @return the started source, or empty when {@code metadata.source} is not set */
    public static Optional<ReloadingMetadataSource> create(String source, String policyUrl, String filePath,
                                                           long pollIntervalMs, String projectId,
                                                           String reservedSchemas, MeterRegistry meters) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        if (policyUrl != null && !policyUrl.isBlank()) {
            throw new IllegalStateException("metadata.source=" + source + " and app.security.rls.policy-url are "
                    + "both set: configure one permission source, not two");
        }
        if (!FileMetadataSource.NAME.equals(source.trim())) {
            throw new IllegalStateException("metadata.source must be \"file\" (url, db and s3 are not available yet) "
                    + "but was \"" + source + "\"");
        }
        require(filePath, "metadata.file.path", "the path of the permissions file or of the folder holding it");
        require(projectId, "app.project-id", "the project the document must be for");
        if (pollIntervalMs <= 0) {
            throw new IllegalStateException("metadata.file.poll-interval-ms must be positive");
        }
        ReloadingMetadataSource created = FileMetadataSource.create(Path.of(filePath),
                Duration.ofMillis(pollIntervalMs), ReservedSchemas.fromConfig(reservedSchemas), projectId, meters);
        created.start();
        return Optional.of(created);
    }

    private static void require(String value, String property, String meaning) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required with metadata.source=file: " + meaning);
        }
    }
}
