package io.github.excalibase.service;

import io.github.excalibase.config.AppConfig;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FullSchemaServiceTest {

    private ServiceLookup serviceLookup;
    private AppConfig appConfig;
    private IDatabaseSchemaReflector reflector;
    private FullSchemaService service;

    @BeforeEach
    void setUp() {
        serviceLookup = mock(ServiceLookup.class);
        appConfig = buildAppConfig();
        reflector = mock(IDatabaseSchemaReflector.class);

        when(serviceLookup.forBean(IDatabaseSchemaReflector.class, "Postgres")).thenReturn(reflector);

        service = new FullSchemaService(serviceLookup, appConfig);
    }

    // ── getFullSchema ───────────────────────────────────────────────

    @Test
    void getFullSchema_returnsReflectedSchema() {
        Map<String, TableInfo> schema = Map.of("users", buildTable("users"));
        when(reflector.reflectSchema()).thenReturn(schema);

        Map<String, TableInfo> result = service.getFullSchema();

        assertThat(result).containsKey("users");
        verify(reflector, times(1)).reflectSchema();
    }

    @Test
    void getFullSchema_cachesResult() {
        Map<String, TableInfo> schema = Map.of("users", buildTable("users"));
        when(reflector.reflectSchema()).thenReturn(schema);

        service.getFullSchema();
        service.getFullSchema();

        // Reflector called only once due to caching
        verify(reflector, times(1)).reflectSchema();
    }

    @Test
    void getFullSchema_wrapsExceptionInRuntimeException() {
        when(reflector.reflectSchema()).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> service.getFullSchema())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unable to reflect database schema");
    }

    // ── refreshFullSchema ───────────────────────────────────────────

    @Test
    void refreshFullSchema_clearsAndReloads() {
        Map<String, TableInfo> schema = Map.of("users", buildTable("users"));
        when(reflector.reflectSchema()).thenReturn(schema);

        service.getFullSchema(); // populate cache
        service.refreshFullSchema();

        // Should have been called twice
        verify(reflector, times(2)).reflectSchema();
    }

    // ── isSchemaLoaded ──────────────────────────────────────────────

    @Test
    void isSchemaLoaded_falseInitially() {
        assertThat(service.isSchemaLoaded()).isFalse();
    }

    @Test
    void isSchemaLoaded_trueAfterGetFullSchema() {
        when(reflector.reflectSchema()).thenReturn(Map.of("users", buildTable("users")));
        service.getFullSchema();
        assertThat(service.isSchemaLoaded()).isTrue();
    }

    // ── clearCache ──────────────────────────────────────────────────

    @Test
    void clearCache_makesSchemaUnloaded() {
        when(reflector.reflectSchema()).thenReturn(Map.of("users", buildTable("users")));
        service.getFullSchema();

        service.clearCache();

        assertThat(service.isSchemaLoaded()).isFalse();
    }

    // ── getCacheStats ───────────────────────────────────────────────

    @Test
    void getCacheStats_whenNotLoaded() {
        Map<String, Object> stats = service.getCacheStats();
        assertThat(stats).containsEntry("isLoaded", false);
    }

    @Test
    void getCacheStats_whenLoaded() {
        TableInfo users = buildTable("users");
        when(reflector.reflectSchema()).thenReturn(Map.of("users", users));
        service.getFullSchema();

        Map<String, Object> stats = service.getCacheStats();
        assertThat(stats)
                .containsEntry("isLoaded", true)
                .containsEntry("tableCount", 1);
    }

    // ── getSchemaSummary ────────────────────────────────────────────

    @Test
    void getSchemaSummary_whenNotLoaded() {
        Map<String, Object> summary = service.getSchemaSummary();
        assertThat(summary).containsEntry("error", "Schema not loaded yet");
    }

    @Test
    void getSchemaSummary_whenLoaded() {
        TableInfo users = buildTable("users");
        when(reflector.reflectSchema()).thenReturn(Map.of("users", users));
        service.getFullSchema();

        Map<String, Object> summary = service.getSchemaSummary();
        assertThat(summary)
                .containsEntry("tableCount", 1)
                .containsKey("tableNames")
                .containsKey("totalColumns")
                .containsKey("averageColumnsPerTable");
    }

    // ── helpers ─────────────────────────────────────────────────────

    private AppConfig buildAppConfig() {
        AppConfig config = new AppConfig();
        AppConfig.CacheConfig cacheConfig = new AppConfig.CacheConfig();
        cacheConfig.setSchemaTtlMinutes(5);

        config.setCache(cacheConfig);
        config.setDatabaseType(io.github.excalibase.constant.DatabaseType.POSTGRES);
        return config;
    }

    private TableInfo buildTable(String name) {
        TableInfo table = new TableInfo();
        table.setName(name);

        ColumnInfo col = new ColumnInfo();
        col.setName("id");
        col.setAliasName("id");
        col.setType("integer");
        col.setPrimaryKey(true);
        col.setNullable(false);

        table.setColumns(List.of(col));
        table.setForeignKeys(List.of());
        return table;
    }
}
