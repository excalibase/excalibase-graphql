package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        return MetadataSources.create(source, policyUrl, path, pollMs, projectId, "", meters);
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
        assertThatThrownBy(() -> create("db", "", "/x", 5000, PROJECT))
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
}
