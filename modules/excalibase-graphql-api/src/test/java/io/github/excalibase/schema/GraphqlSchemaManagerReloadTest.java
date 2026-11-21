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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** What reloading the configured database does to what is served, and to what a request in flight caches. */
class GraphqlSchemaManagerReloadTest {

    private static final String ORDERS = "public.orders";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Reflects a table whose columns the test chooses, and fails on demand. */
    private static class ScriptedManager extends GraphqlSchemaManager {

        List<String> columns = List.of("id");
        boolean databaseDown;

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

    /** Lets one request stall inside the permission read, after it has read the reflection. */
    private static final class StallsOneRequest implements PermissionProvider {
        private final CountDownLatch inside = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean stallNext;

        @Override
        public PermissionSet permissionsFor(String projectId) {
            if (stallNext && Thread.currentThread().getName().startsWith("stalled-request")) {
                inside.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never released the request");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return emptyDocument(projectId);
        }

        @Override
        public void evict(String projectId) {
            // nothing cached
        }
    }

    private static PermissionSet emptyDocument(String projectId) {
        try {
            return PermissionSetParser.parse(MAPPER.readTree("{\"projectId\":\"" + projectId
                    + "\",\"version\":1,\"tables\":[],\"functions\":[],\"functionPermissions\":[]}"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Starts a request in the window between the new reflection being set and the caches being emptied. */
    private static final class RequestInTheWindow extends ScriptedManager {
        private final StallsOneRequest permissions;
        private final ExecutorService requests = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "stalled-request"));
        private Future<?> stalled;
        private Principal caller;
        private boolean finishRequestAfterClear;

        RequestInTheWindow(StallsOneRequest permissions) {
            super(permissions, true);
            this.permissions = permissions;
        }

        @Override
        void betweenReflectionAndCacheClear() {
            if (!permissions.stallNext) {
                return;
            }
            stalled = requests.submit(() -> resolveEngineState("acme", "proj-1", caller));
            try {
                if (!permissions.inside.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the request never reached the permission read");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        void afterCacheClear() {
            if (!finishRequestAfterClear) {
                return;
            }
            permissions.release.countDown();
            try {
                stalled.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    @DisplayName("a request that starts while a reload is publishing does not cache an engine of the old schema")
    void requestStartedInsideThePublishWindowCachesNothingStale() throws Exception {
        StallsOneRequest permissions = new StallsOneRequest();
        RequestInTheWindow manager = new RequestInTheWindow(permissions);
        manager.init();
        JwtClaims claims = new JwtClaims("u-1", "proj-1", "acme", "demo", "", "service", "apikey:1", "service", 1L);
        manager.caller = new Principal(Principal.SERVICE, true, claims, java.util.Map.of());
        manager.resolveEngineState("acme", "proj-1", manager.caller);   // the old reflection is now cached

        manager.columns = List.of("id", "added");
        permissions.stallNext = true;
        manager.reload();
        permissions.release.countDown();
        manager.stalled.get(10, TimeUnit.SECONDS);
        manager.requests.shutdown();

        var next = manager.resolveEngineState("acme", "proj-1", manager.caller);
        assertThat(next.compiler().schemaInfo().getColumns(ORDERS)).contains("added");
    }

    @Test
    @DisplayName("a request that finishes after the caches are emptied and before the publish ends caches nothing stale")
    void requestFinishingBeforeThePublishEndsCachesNothingStale() throws Exception {
        StallsOneRequest permissions = new StallsOneRequest();
        RequestInTheWindow manager = new RequestInTheWindow(permissions);
        manager.init();
        JwtClaims claims = new JwtClaims("u-1", "proj-1", "acme", "demo", "", "service", "apikey:1", "service", 1L);
        manager.caller = new Principal(Principal.SERVICE, true, claims, java.util.Map.of());
        manager.resolveEngineState("acme", "proj-1", manager.caller);

        manager.columns = List.of("id", "added");
        permissions.stallNext = true;
        manager.finishRequestAfterClear = true;
        manager.reload();
        manager.requests.shutdown();

        var next = manager.resolveEngineState("acme", "proj-1", manager.caller);
        assertThat(next.compiler().schemaInfo().getColumns(ORDERS)).contains("added");
    }

    @Test
    @DisplayName("engines are cached after the first reflection and after each reload: one request, one engine")
    void enginesAreCachedAgainAfterAReload() {
        ScriptedManager manager = new ScriptedManager(new StallsOneRequest(), true);
        manager.init();
        JwtClaims claims = new JwtClaims("u-1", "proj-1", "acme", "demo", "", "service", "apikey:1", "service", 1L);
        Principal service = new Principal(Principal.SERVICE, true, claims, java.util.Map.of());

        assertThat(manager.resolveEngineState("acme", "proj-1", service))
                .isSameAs(manager.resolveEngineState("acme", "proj-1", service));

        manager.columns = List.of("id", "added");
        manager.reload();
        assertThat(manager.resolveEngineState("acme", "proj-1", service))
                .isSameAs(manager.resolveEngineState("acme", "proj-1", service));

        manager.reload();
        assertThat(manager.resolveEngineState("acme", "proj-1", service))
                .isSameAs(manager.resolveEngineState("acme", "proj-1", service));
    }
}
