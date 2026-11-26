package io.github.excalibase.observability.actuator;

import io.github.excalibase.document.CountingProvider;
import io.github.excalibase.document.DocumentEngines;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** A wrong or unreachable document database fails readiness, as an unreachable SQL one does. */
class DocumentDatabaseHealthTest {

    @Test
    void healthFollowsTheDocumentDatabase() throws IOException {
        Path collections = Files.createTempFile("collections", ".yaml");
        Files.writeString(collections, "collections:\n  - name: orders\n    fields:\n      owner: string\n");
        PermissionSet document = new PermissionSet("p1", 1, List.of(), List.of(), List.of());
        PermissionProvider permissions = new PermissionProvider() {
            @Override public PermissionSet permissionsFor(String projectId) { return document; }
            @Override public void evict(String projectId) { /* nothing is cached */ }
        };
        try (DocumentEngines engines = new DocumentEngines(CountingProvider.TYPE, "", "app", collections.toString(), 1000, 30,
                "p1", permissions)) {
            DatabaseHealth health = new DatabaseHealth(mock(DataSource.class), true, engines);
            CountingProvider.HEALTHY.set(true);
            assertThat(health.status()).isEqualTo(HealthStatus.UP);

            CountingProvider.HEALTHY.set(false);
            assertThat(health.status()).isEqualTo(HealthStatus.DOWN);
        } finally {
            CountingProvider.HEALTHY.set(true);
        }
    }
}
