package io.github.excalibase.schema;

import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionSetParser;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.Principal;
import io.github.excalibase.spi.SqlEngine;
import io.github.excalibase.spi.SqlEngineFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What reloading the configured database does to what is served, and to what a request in flight caches. */
class GraphqlSchemaManagerReloadTest {

    private static final String ORDERS = "public.orders";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Reflects a table whose columns the test chooses, and fails on demand. */
    private static class ScriptedManager extends GraphqlSchemaManager {

        private List<String> columns = List.of("id");
        private boolean databaseDown;

        ScriptedManager(PermissionProvider permissions, boolean jwtEnabled) {
            super(null, 30, "postgres", DEFAULT_MAX_QUERY_DEPTH, 30, "", null, null, permissions, jwtEnabled);
        }

        Reflection reflectionNow(SqlEngine engine) {
            SchemaInfo schema = new SchemaInfo();
            schema.setTableSchema(ORDERS, "public");
            columns.forEach(column -> schema.addColumn(ORDERS, column, "text"));
            schema.addPrimaryKey(ORDERS, "id");
            return new Reflection(schema, "public", "postgres", engine, null, (sql, params) -> false);
        }

        @Override
        Reflection loadDefault(SqlEngine engine) {
            if (databaseDown) {
                throw new IllegalStateException("database down");
            }
            return reflectionNow(engine);
        }

        @Override
        Reflection reflect(String orgSlug, String projectId) {
            return reflectionNow(SqlEngineFactory.create("postgres"));
        }
    }

    @Test
    @DisplayName("a reload that cannot read the database keeps the schema that was served")
    void failedReloadKeepsThePreviousSchema() {
        ScriptedManager manager = new ScriptedManager(null, false);
        manager.init();
        var before = manager.getEngineState();
        assertThat(before.compiler().schemaInfo().getTableNames()).containsExactly(ORDERS);

        manager.databaseDown = true;
        manager.reload();

        assertThat(manager.getEngineState()).isSameAs(before);
        assertThat(manager.getEngineState().compiler().schemaInfo().getTableNames()).containsExactly(ORDERS);
    }

    @Test
    @DisplayName("a reload that can read the database serves the new schema")
    void successfulReloadServesTheNewSchema() {
        ScriptedManager manager = new ScriptedManager(null, false);
        manager.init();

        manager.columns = List.of("id", "added");
        manager.reload();

        assertThat(manager.getEngineState().compiler().schemaInfo().getColumns(ORDERS)).contains("added");
    }

    @Test
    @DisplayName("a schema that cannot be read at start is served empty, and a later reload fills it")
    void unreadableAtStartIsEmptyThenFills() {
        ScriptedManager manager = new ScriptedManager(null, false);
        manager.databaseDown = true;
        manager.init();
        assertThat(manager.getEngineState().compiler().schemaInfo().getTableNames()).isEmpty();

        manager.databaseDown = false;
        manager.reload();

        assertThat(manager.getEngineState().compiler().schemaInfo().getTableNames()).containsExactly(ORDERS);
    }

    /** Replaces the reflection while a request is between reading it and caching what it built. */
    private static final class ReloadsMidRequest implements PermissionProvider {
        private ScriptedManager manager;
        private boolean reloaded;

        @Override
        public PermissionSet permissionsFor(String projectId) {
            if (!reloaded) {
                reloaded = true;
                manager.columns = List.of("id", "added");
                manager.reload();
            }
            try {
                return PermissionSetParser.parse(MAPPER.readTree("{\"projectId\":\"" + projectId
                        + "\",\"version\":1,\"tables\":[],\"functions\":[],\"functionPermissions\":[]}"));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public void evict(String projectId) {
            // nothing cached
        }
    }

    @Test
    @DisplayName("an engine built from the old reflection while a reload ran is not served to the next request")
    void staleEngineIsNotCachedAcrossAReload() {
        ReloadsMidRequest permissions = new ReloadsMidRequest();
        ScriptedManager manager = new ScriptedManager(permissions, true);
        permissions.manager = manager;
        manager.init();
        JwtClaims claims = new JwtClaims("u-1", "proj-1", "acme", "demo", "", "service", "apikey:1", "service", 1L);
        Principal service = new Principal(Principal.SERVICE, true, claims, java.util.Map.of());

        manager.resolveEngineState("acme", "proj-1", service);
        var next = manager.resolveEngineState("acme", "proj-1", service);

        assertThat(next.compiler().schemaInfo().getColumns(ORDERS)).contains("added");
    }
}
