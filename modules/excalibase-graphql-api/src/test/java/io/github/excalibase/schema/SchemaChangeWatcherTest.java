package io.github.excalibase.schema;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaChangeWatcherTest {

    private final GraphqlSchemaManager schemas = mock(GraphqlSchemaManager.class);

    private SchemaChangeWatcher watcher(long seconds, boolean nats, DynamicDataSourceManager tenants) {
        SchemaChangeWatcher watcher = new SchemaChangeWatcher(schemas, seconds, nats, tenants);
        watcher.start();
        return watcher;
    }

    @Test
    @DisplayName("a standalone engine polls")
    void standalonePolls() {
        SchemaChangeWatcher watcher = watcher(5, false, null);
        assertThat(watcher.running()).isTrue();
        watcher.stop();
        assertThat(watcher.running()).isFalse();
    }

    @Test
    @DisplayName("with NATS the DDL events do the work, so nothing is polled")
    void natsMeansNoPolling() {
        assertThat(watcher(5, true, null).running()).isFalse();
    }

    @Test
    @DisplayName("with tenant routing nothing is polled")
    void tenantRoutingMeansNoPolling() {
        assertThat(watcher(5, false, mock(DynamicDataSourceManager.class)).running()).isFalse();
    }

    @Test
    @DisplayName("zero turns it off")
    void zeroTurnsItOff() {
        assertThat(watcher(0, false, null).running()).isFalse();
    }

    @Test
    @DisplayName("a poll asks the manager to reload if the schema changed")
    void pollAsksTheManager() {
        watcher(5, false, null).poll();
        verify(schemas, times(1)).reloadIfSchemaChanged();
    }

    @Test
    @DisplayName("a database that is down does not stop the polling")
    void failureIsSurvived() {
        when(schemas.reloadIfSchemaChanged()).thenThrow(new DataAccessResourceFailureException("down"));
        SchemaChangeWatcher watcher = watcher(5, false, null);

        assertThatCode(() -> {
            watcher.poll();
            watcher.poll();
        }).doesNotThrowAnyException();
        verify(schemas, times(2)).reloadIfSchemaChanged();
        watcher.stop();
    }
}
