package io.github.excalibase.schema;

import io.github.excalibase.access.ProbeRunner;
import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.config.datasource.TenantDatabase;
import org.junit.jupiter.api.Test;
import io.github.excalibase.service.DatabaseEngine;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A cached schema outlives the pool it was read through, so its realtime probes take the project's current pool on
 * every run, never the one that was open at reflection. (EXC-743)
 */
class GraphqlSchemaManagerRetiredPoolTest {

    private static final String ORG = "acme";
    private static final String PROJECT = "proj-a";
    private static final String PROBE = "SELECT EXISTS (SELECT 1)";

    private static GraphqlSchemaManager managerOver(DynamicDataSourceManager pools) {
        return new GraphqlSchemaManager(null, 30, "postgres", GraphqlSchemaManager.DEFAULT_MAX_QUERY_DEPTH, 30, "",
                null, pools, null, false);
    }

    @Test
    void everyProbeTakesTheProjectsPoolAsItIsThen_soARetiredPoolIsNeverReused() {
        DynamicDataSourceManager pools = mock(DynamicDataSourceManager.class);
        SQLiteDataSource first = new SQLiteDataSource();
        first.setUrl("jdbc:sqlite::memory:");
        SQLiteDataSource second = new SQLiteDataSource();
        second.setUrl("jdbc:sqlite::memory:");
        when(pools.getDatabase(ORG, PROJECT))
                .thenReturn(new TenantDatabase(first, null))
                .thenReturn(new TenantDatabase(second, null));
        ProbeRunner probes = managerOver(pools).tenantProbes(ORG, PROJECT);

        assertThat(probes.exists(PROBE, Map.of())).isTrue();
        assertThat(probes.exists(PROBE, Map.of())).isTrue();

        verify(pools, times(2)).getDatabase(ORG, PROJECT);
    }

    @Test
    void aSchemaReflectedThroughOnePool_probesOnTheNextOne(@TempDir Path directory) throws Exception {
        SQLiteDataSource reflected = sqliteFile(directory.resolve("reflected.db"), "reflected_only");
        SQLiteDataSource current = sqliteFile(directory.resolve("current.db"), "current_only");
        DynamicDataSourceManager pools = mock(DynamicDataSourceManager.class);
        when(pools.getDatabase(ORG, PROJECT))
                .thenReturn(new TenantDatabase(reflected, DatabaseEngine.SQLITE))
                .thenReturn(new TenantDatabase(current, DatabaseEngine.SQLITE));

        ProbeRunner probes = managerOver(pools).reflectTenant(ORG, PROJECT).probes();

        assertThat(probes.exists("SELECT EXISTS (SELECT 1 FROM current_only)", Map.of())).isTrue();
    }

    private static SQLiteDataSource sqliteFile(Path file, String table) throws SQLException {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + file);
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO " + table + " VALUES (1)");
        }
        return source;
    }
}
