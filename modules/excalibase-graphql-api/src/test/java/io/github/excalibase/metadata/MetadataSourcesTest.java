package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataSourcesTest {

    @TempDir
    Path dir;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private Optional<ReloadingMetadataSource> create(String source, String policyUrl, String path, long pollMs,
                                                     String projectId) {
        return MetadataSources.create(new MetadataSettings(source, policyUrl, projectId, "",
                new MetadataSettings.FileSettings(path, pollMs), noUrl(), false), meters);
    }

    private static UrlSettings noUrl() {
        return new UrlSettings("", "", "", false, Duration.ofSeconds(5), Duration.ofSeconds(3),
                Duration.ofSeconds(10));
    }

    private Optional<ReloadingMetadataSource> createUrl(String location, boolean allowInsecure) {
        return MetadataSources.create(new MetadataSettings("url", "", PROJECT, "", new MetadataSettings.FileSettings("", 5000),
                new UrlSettings(location, "", "", allowInsecure, Duration.ofHours(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(1)), false), meters);
    }

    @Test
    void withNoSourceSet_nothingIsCreated_soTodaysBehaviourStands() {
        assertThat(create("", "", "", 5000, PROJECT)).isEmpty();
        assertThat(create(null, "http://provisioning/api", "", 5000, PROJECT)).isEmpty();
    }

    @Test
    void theFileSource_isStartedAndServes() throws IOException {
        Path file = Files.writeString(dir.resolve("permissions.yaml"), GOOD_YAML);

        try (ReloadingMetadataSource source = create("file", "", file.toString(), 5000, PROJECT).orElseThrow()) {
            assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        }
    }

    @Test
    void theFileSource_startedOnABadDocument_refusesAll() throws IOException {
        Path file = Files.writeString(dir.resolve("permissions.yaml"), BAD_RULE);

        try (ReloadingMetadataSource source = create("file", "", file.toString(), 5000, PROJECT).orElseThrow()) {
            assertThatThrownBy(() -> source.permissionsFor(PROJECT))
                    .isInstanceOf(PermissionsUnavailableException.class);
        }
    }

    @Test
    void twoSources_areRefusedAtStart() {
        assertThatThrownBy(() -> create("file", "http://provisioning/api", "/x", 5000, PROJECT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("policy-url");
    }

    @Test
    void anUnsupportedSource_isRefusedAtStart() {
        assertThatThrownBy(() -> create("s3", "", "/x", 5000, PROJECT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("metadata.source");
    }

    @Test
    void aFileSourceWithoutAPathOrProject_isRefusedAtStart() {
        assertThatThrownBy(() -> create("file", "", "", 5000, PROJECT))
                .hasMessageContaining("metadata.file.path");
        assertThatThrownBy(() -> create("file", "", "/x", 5000, ""))
                .hasMessageContaining("app.project-id");
        assertThatThrownBy(() -> create("file", "", "/x", 0, PROJECT))
                .hasMessageContaining("poll-interval-ms");
    }

    @Test
    void aDocumentThatMakesTheParserThrow_doesNotCrashStartup_andRefusesAll() throws IOException {
        Path file = Files.writeString(dir.resolve("permissions.yaml"), GOOD_YAML + "~: x\n");

        try (ReloadingMetadataSource source = create("file", "", file.toString(), 5000, PROJECT).orElseThrow()) {
            assertThatThrownBy(() -> source.permissionsFor(PROJECT))
                    .isInstanceOf(PermissionsUnavailableException.class);
            assertThat(source.status().lastRefusal()).isEqualTo("invalid_document");
        }
    }

    @Test
    void aUrlSourceWithoutALocation_isRefusedAtStart() {
        assertThatThrownBy(() -> createUrl("", false)).hasMessageContaining("metadata.url.location");
    }

    @Test
    void aUrlSourceAlongsideAPolicyUrl_isRefused() {
        MetadataSettings both = new MetadataSettings("url", "http://provisioning/api", PROJECT, "",
                new MetadataSettings.FileSettings("", 5000), noUrl(), false);

        assertThatThrownBy(() -> MetadataSources.create(both, meters)).hasMessageContaining("policy-url");
    }

    @Test
    void aUrlSourceWithNoReachableServer_startsAndRefusesAll() {
        try (ReloadingMetadataSource source = createUrl("http://127.0.0.1:1/permissions.yaml", false).orElseThrow()) {
            assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
            assertThat(source.name()).isEqualTo("url");
        }
    }

    private Optional<ReloadingMetadataSource> createDb(String url, Duration interval) {
        return MetadataSources.create(new MetadataSettings("db", "", PROJECT, "", new MetadataSettings.FileSettings("", 5000),
                noUrl(), new DbSettings(url, "u", "p", false, interval, Duration.ofSeconds(1)), S3Settings.NONE, false), meters);
    }

    @Test
    void aDbSource_withNoReachableDatabase_startsAndRefusesAll() {
        try (ReloadingMetadataSource source = createDb("jdbc:postgresql://127.0.0.1:1/db", Duration.ofHours(1))
                .orElseThrow()) {
            assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
            assertThat(source.name()).isEqualTo("db");
        }
    }

    @Test
    void aDbSource_withoutADatabase_aPositiveInterval_orPostgres_isRefusedAtStart() {
        assertThatThrownBy(() -> createDb("", Duration.ofSeconds(5))).hasMessageContaining("spring.datasource.url");
        assertThatThrownBy(() -> createDb("jdbc:postgresql://h/db", Duration.ZERO)).hasMessageContaining("poll-interval-ms");
        assertThatThrownBy(() -> createDb("jdbc:mysql://h/db", Duration.ofSeconds(5))).hasMessageContaining("Postgres only");
    }

    private static S3Settings s3(String endpoint, String bucket, Duration interval, S3Settings.Credentials credentials) {
        return new S3Settings(endpoint, bucket, "permissions.yaml", "us-east-1", null, false, credentials, interval,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private Optional<ReloadingMetadataSource> createS3(S3Settings s3) {
        return MetadataSources.create(new MetadataSettings("s3", "", PROJECT, "", new MetadataSettings.FileSettings("", 5000),
                noUrl(), DbSettings.NONE, s3, false), meters);
    }

    private static final S3Settings.Credentials KEYS = S3Settings.Credentials.literal("id", "secret");

    @Test
    void anS3Source_withNoReachableStore_startsAndRefusesAll() {
        try (ReloadingMetadataSource source = createS3(s3("http://127.0.0.1:1", "meta", Duration.ofHours(1), KEYS))
                .orElseThrow()) {
            assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
            assertThat(source.name()).isEqualTo("s3");
        }
    }

    @Test
    void anS3Source_withoutABucket_credentials_orAPositiveInterval_isRefusedAtStart() {
        assertThatThrownBy(() -> createS3(s3("http://127.0.0.1:1", "", Duration.ofHours(1), KEYS)))
                .hasMessageContaining("metadata.s3.bucket");
        assertThatThrownBy(() -> createS3(s3("http://127.0.0.1:1", "meta", Duration.ofHours(1), S3Settings.Credentials.NONE)))
                .hasMessageContaining("metadata.s3.access-key-id").hasMessageContaining("AWS_ACCESS_KEY_ID");
        assertThatThrownBy(() -> createS3(s3("http://127.0.0.1:1", "meta", Duration.ZERO, KEYS)))
                .hasMessageContaining("poll-interval-ms");
    }

    @Test
    void anS3SourceAlongsideAPolicyUrl_isRefused() {
        MetadataSettings both = new MetadataSettings("s3", "http://provisioning/api", PROJECT, "",
                new MetadataSettings.FileSettings("", 5000), noUrl(), DbSettings.NONE,
                s3("http://127.0.0.1:1", "meta", Duration.ofHours(1), KEYS), false);

        assertThatThrownBy(() -> MetadataSources.create(both, meters)).hasMessageContaining("policy-url");
    }

    @Test
    void anUnknownSource_namesTheFourThatExist() {
        assertThatThrownBy(() -> create("gcs", "", "", 5000, PROJECT)).hasMessageContaining("\"s3\"");
    }
}
