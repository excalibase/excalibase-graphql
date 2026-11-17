package io.github.excalibase.observability.actuator;

import io.github.excalibase.metadata.FileMetadataSource;
import io.github.excalibase.metadata.MetadataHealth;
import io.github.excalibase.metadata.ReloadingMetadataSource;
import io.github.excalibase.permissions.NoPermissionSource;
import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InfoActuatorTest {

    @TempDir
    Path dir;

    @Test
    void withoutAMetadataSource_infoIsEmpty_asBefore() {
        assertThat(new InfoActuator(null).info()).isEmpty();
        assertThat(new InfoActuator(new MetadataHealth(new NoPermissionSource())).info()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void withAMetadataSource_infoReportsThisReplicasVersionAndLastRefusal() throws IOException {
        Path file = Files.writeString(dir.resolve("permissions.yaml"),
                "format: 1\nprojectId: demo\ntables: []\n");
        try (ReloadingMetadataSource source = FileMetadataSource.create(file, Duration.ofHours(1),
                ReservedSchemas.fromConfig(""), "demo", new SimpleMeterRegistry())) {
            source.start();
            Files.writeString(file, "format: 1\nprojectId: demo\nsurprise: 1\n");
            source.refresh();
            source.refresh();

            Map<String, Object> metadata = (Map<String, Object>) new InfoActuator(new MetadataHealth(source)).info().get("metadata");

            assertThat(metadata).containsEntry("source", "file").containsEntry("version", 1L);
            assertThat(metadata).containsEntry("lastRefusal", "invalid_document");
            assertThat(metadata.toString()).doesNotContain("surprise");
            assertThat((String) metadata.get("sourceVersion")).hasSize(64);
        }
    }
}
