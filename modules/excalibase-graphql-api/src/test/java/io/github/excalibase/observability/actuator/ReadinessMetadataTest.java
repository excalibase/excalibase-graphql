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

import static org.assertj.core.api.Assertions.assertThat;

/** A replica with no good metadata refuses every read, so it must not be routed to. */
class ReadinessMetadataTest {

    @TempDir
    Path dir;

    private ReloadingMetadataSource open(Path file) {
        ReloadingMetadataSource source = FileMetadataSource.create(file, Duration.ofHours(1),
                ReservedSchemas.fromConfig(""), "demo", new SimpleMeterRegistry(), false);
        source.start();
        return source;
    }

    @Test
    void withoutAMetadataSource_readinessIsUnchanged() {
        assertThat(new ReadinessState().status()).isEqualTo(HealthStatus.UP);
        assertThat(new ReadinessState(new MetadataHealth(new NoPermissionSource())).status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void notReadyWhileNoGoodDocumentIsLoaded_readyOnceOneIs() throws IOException {
        Path file = Files.writeString(dir.resolve("permissions.yaml"), "format: 1\nprojectId: demo\nbogus: 1\n");
        try (ReloadingMetadataSource source = open(file)) {
            ReadinessState readiness = new ReadinessState(new MetadataHealth(source));
            assertThat(readiness.status()).isEqualTo(HealthStatus.OUT_OF_SERVICE);

            Files.writeString(file, "format: 1\nprojectId: demo\ntables: []\n");
            source.refresh();
            source.refresh();
            assertThat(readiness.status()).isEqualTo(HealthStatus.UP);

            Files.writeString(file, "format: 1\nprojectId: demo\nbogus: 1\n");
            source.refresh();
            source.refresh();
            assertThat(readiness.status()).as("last good version still served").isEqualTo(HealthStatus.UP);
        }
    }
}
