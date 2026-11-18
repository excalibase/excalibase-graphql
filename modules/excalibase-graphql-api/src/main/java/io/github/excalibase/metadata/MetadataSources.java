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
    public static Optional<ReloadingMetadataSource> create(MetadataSettings settings, MeterRegistry meters) {
        String source = settings.source();
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        if (settings.policyUrl() != null && !settings.policyUrl().isBlank()) {
            throw new IllegalStateException("metadata.source=" + source + " and app.security.rls.policy-url are "
                    + "both set: configure one permission source, not two");
        }
        require(settings.projectId(), "app.project-id", source, "the project the document must be for");
        ReservedSchemas reserved = ReservedSchemas.fromConfig(settings.reservedSchemas());
        ReloadingMetadataSource created = switch (source.trim()) {
            case FileMetadataSource.NAME -> file(settings, reserved, meters);
            case UrlMetadataSource.NAME -> UrlMetadataSource.create(positive(settings.url()), reserved,
                    settings.projectId(), meters, settings.envJwtConfigured());
            case DbMetadataSource.NAME -> DbMetadataSource.create(db(settings.db()), reserved, settings.projectId(),
                    meters, settings.envJwtConfigured());
            case S3MetadataSource.NAME -> S3MetadataSource.create(positive(settings.s3()), reserved,
                    settings.projectId(), meters, settings.envJwtConfigured());
            default -> throw new IllegalStateException("metadata.source must be \"file\", \"url\", \"db\" or "
                    + "\"s3\" but was \"" + source + "\"");
        };
        created.start();
        return Optional.of(created);
    }

    private static ReloadingMetadataSource file(MetadataSettings settings, ReservedSchemas reserved,
                                                MeterRegistry meters) {
        MetadataSettings.FileSettings file = settings.file();
        require(file.path(), "metadata.file.path", "file", "the path of the permissions file or of the folder holding it");
        if (file.pollIntervalMs() <= 0) {
            throw new IllegalStateException("metadata.file.poll-interval-ms must be positive");
        }
        return FileMetadataSource.create(Path.of(file.path()), Duration.ofMillis(file.pollIntervalMs()), reserved,
                settings.projectId(), meters, settings.envJwtConfigured());
    }

    private static UrlSettings positive(UrlSettings url) {
        if (url.pollInterval().isZero() || url.pollInterval().isNegative() || url.connectTimeout().isZero()
                || url.connectTimeout().isNegative() || url.readTimeout().isZero() || url.readTimeout().isNegative()) {
            throw new IllegalStateException("metadata.url.poll-interval-ms, connect-timeout-ms and read-timeout-ms "
                    + "must be positive");
        }
        return url;
    }

    private static S3Settings positive(S3Settings s3) {
        if (s3.pollInterval().isZero() || s3.pollInterval().isNegative() || s3.connectTimeout().isZero()
                || s3.connectTimeout().isNegative() || s3.readTimeout().isZero() || s3.readTimeout().isNegative()) {
            throw new IllegalStateException("metadata.s3.poll-interval-ms, connect-timeout-ms and read-timeout-ms "
                    + "must be positive");
        }
        return s3;
    }

    private static DbSettings db(DbSettings db) {
        require(db.url(), "spring.datasource.url or metadata.database.url", "db", "the database holding the document");
        if (db.pollInterval().isZero() || db.pollInterval().isNegative() || db.connectTimeout().isZero()
                || db.connectTimeout().isNegative()) {
            throw new IllegalStateException("metadata.db.poll-interval-ms and connect-timeout-ms must be positive");
        }
        return db;
    }

    private static void require(String value, String property, String source, String meaning) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required with metadata.source=" + source + ": " + meaning);
        }
    }
}
