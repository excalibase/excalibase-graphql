package io.github.excalibase.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.permissions.NoPermissionSource;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionSetParser;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.Principal;
import io.github.excalibase.spi.SqlEngineFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A project's database is reflected once; each role gets its own engine built from that reflection
 * and its plan, rebuilt when the permission document moves to a new version, and dropped with the
 * reflection when the project's permissions or schema change.
 */
class GraphqlSchemaManagerCacheTest {

    private static final String ORDERS = "public.orders";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StubPermissions permissions;
    private CountingSchemaManager manager;

    /** Serves one document per project, whose version the test moves. */
    private static final class StubPermissions implements PermissionProvider {
        private final List<String> reads = new ArrayList<>();
        private long version = 1;
        private boolean unavailable;

        @Override
        public PermissionSet permissionsFor(String projectId) {
            reads.add(projectId);
            if (unavailable) {
                throw new PermissionsUnavailableException("control plane down");
            }
            return parse("{\"projectId\":\"" + projectId + "\",\"version\":" + version + ",\"tables\":["
                    + "{\"table\":\"public.orders\",\"role\":\"user\","
                    + "\"select\":{\"filter\":{},\"columns\":[\"id\"]}}"
                    + "],\"functions\":[],\"functionPermissions\":[]}");
        }

        @Override
        public void evict(String projectId) {
            // nothing cached
        }
    }

    /** Reflects a fixed schema without a database and counts reflections. */
    private static final class CountingSchemaManager extends GraphqlSchemaManager {

        private final List<String> reflected = new ArrayList<>();

        private CountingSchemaManager(PermissionProvider permissionProvider, boolean jwtEnabled) {
            super(null, null, 30, "postgres", DEFAULT_MAX_QUERY_DEPTH, 30, "", null, null, permissionProvider,
                    jwtEnabled);
        }

        @Override
        Reflection reflect(String orgSlug, String projectId) {
            reflected.add(projectId);
            SchemaInfo schema = new SchemaInfo();
            schema.setTableSchema(ORDERS, "public");
            schema.addColumn(ORDERS, "id", "integer");
            schema.addColumn(ORDERS, "secret", "text");
            schema.addPrimaryKey(ORDERS, "id");
            return new Reflection(schema, "public", SqlEngineFactory.create("postgres"), null, (sql, params) -> false);
        }
    }

    private static PermissionSet parse(String json) {
        try {
            return PermissionSetParser.parse(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Principal runningAs(String role) {
        JwtClaims claims = new JwtClaims("u-1", "proj-1", "acme", "demo", "", "user", "u@x.com", null, 0L,
                Map.of(), role.equals("user") ? Set.of("user") : Set.of("user", role));
        return new Principal(role, false, claims, Map.of());
    }

    private static Principal service() {
        JwtClaims claims = new JwtClaims("u-1", "proj-1", "acme", "demo", "", "service", "apikey:1",
                "service", 1L);
        return new Principal(Principal.SERVICE, true, claims, Map.of());
    }

    @BeforeEach
    void setUp() {
        permissions = new StubPermissions();
        manager = new CountingSchemaManager(permissions, true);
    }

    @Test
    void theDatabase_isReflectedOncePerProject_forEveryRole() {
        manager.resolveEngineState("acme", "proj-1", runningAs("user"));
        manager.resolveEngineState("acme", "proj-1", Principal.anonymous());
        manager.resolveEngineState("acme", "proj-1", service());

        assertThat(manager.reflected).containsExactly("proj-1");
    }

    @Test
    void eachRole_isServedItsOwnView() {
        var user = manager.resolveEngineState("acme", "proj-1", runningAs("user"));
        var anon = manager.resolveEngineState("acme", "proj-1", Principal.anonymous());
        var svc = manager.resolveEngineState("acme", "proj-1", service());

        assertThat(user.compiler().schemaInfo().getColumns(ORDERS)).containsExactly("id");
        assertThat(anon.compiler().schemaInfo().getTableNames()).isEmpty();
        assertThat(svc.plan().allAccess()).isTrue();
        assertThat(svc.compiler().schemaInfo().getColumns(ORDERS)).contains("secret");
    }

    @Test
    void theSameRoleAndDocument_reuseOneEngine() {
        var first = manager.resolveEngineState("acme", "proj-1", runningAs("user"));
        var second = manager.resolveEngineState("acme", "proj-1", runningAs("user"));

        assertThat(second).isSameAs(first);
    }

    @Test
    void aNewDocumentVersion_rebuildsTheRolesEngine() {
        var first = manager.resolveEngineState("acme", "proj-1", runningAs("user"));
        permissions.version = 2;

        var second = manager.resolveEngineState("acme", "proj-1", runningAs("user"));

        assertThat(second).isNotSameAs(first);
        assertThat(second.permissionsVersion()).isEqualTo(2);
        assertThat(manager.reflected).containsExactly("proj-1");
    }

    @Test
    void service_needsNoDocument() {
        permissions.unavailable = true;

        assertThat(manager.resolveEngineState("acme", "proj-1", service()).plan().allAccess()).isTrue();
        assertThat(permissions.reads).isEmpty();
        assertThatThrownBy(() -> manager.resolveEngineState("acme", "proj-1", runningAs("user")))
                .isInstanceOf(PermissionsUnavailableException.class);
    }

    @Test
    void withoutAPermissionSource_onlyServiceIsServedTables() {
        CountingSchemaManager unsourced = new CountingSchemaManager(new NoPermissionSource(), true);

        assertThat(unsourced.resolveEngineState("acme", "proj-1", runningAs("user"))
                .compiler().schemaInfo().getTableNames()).isEmpty();
        assertThat(unsourced.resolveEngineState("acme", "proj-1", service())
                .compiler().schemaInfo().getTableNames()).containsExactly(ORDERS);
    }

    @Test
    void noPrincipalWhilePermissionsApply_refusesToGuessARole() {
        assertThatThrownBy(() -> manager.resolveEngineState("acme", "proj-1", null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manager.reflected).isEmpty();
    }

    @Test
    void mysqlWithAPermissionSource_isRefusedAtStartup() {
        assertThatThrownBy(() -> new GraphqlSchemaManager(null, null, 30, "mysql", 15, 30, "", null, null,
                permissions, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Postgres-only");
        new GraphqlSchemaManager(null, null, 30, "mysql", 15, 30, "", null, null, new NoPermissionSource(), true);
        new GraphqlSchemaManager(null, null, 30, "mysql", 15, 30, "", null, null, null, false);
    }

    @Test
    void withAuthenticationOff_theWholeSchemaIsServed() {
        CountingSchemaManager insecureDev = new CountingSchemaManager(null, false);

        assertThat(insecureDev.resolveEngineState("acme", "proj-1", null).plan().allAccess()).isTrue();
    }

    @Test
    void evict_dropsTheReflectionAndEveryRolesEngine() {
        var user = manager.resolveEngineState("acme", "proj-1", runningAs("user"));
        manager.resolveEngineState("acme", "proj-2", Principal.anonymous());

        manager.evict("proj-1");

        assertThat(manager.resolveEngineState("acme", "proj-1", runningAs("user"))).isNotSameAs(user);
        manager.resolveEngineState("acme", "proj-2", Principal.anonymous());
        assertThat(manager.reflected).containsExactly("proj-1", "proj-2", "proj-1");
    }

    @Test
    void noProject_returnsTheUnscopedStateWithoutReflecting() {
        assertThat(manager.resolveEngineState("acme", null, Principal.anonymous())).isNull();
        assertThat(manager.reflected).isEmpty();
    }

    @Test
    void realtime_offersOnlyTablesTheRoleCanSelect() {
        assertThat(manager.realtime("acme", "proj-1", runningAs("user"), "public_orders")).isPresent();
        assertThat(manager.realtime("acme", "proj-1", runningAs("user"), "orders")).isPresent();
        assertThat(manager.realtime("acme", "proj-1", Principal.anonymous(), "public_orders")).isEmpty();
    }

    /** The project comes from the request path; the role is the one the request runs as. */
    @Nested
    @DisplayName("request principal")
    class RequestPrincipal {

        @AfterEach
        void clearTenant() {
            TenantContext.clear();
        }

        @BeforeEach
        void setTenant() {
            TenantContext.setTenantId("proj-1");
            TenantContext.setOrgSlug("acme");
        }

        @Test
        void planFor_isTheRequestProjectsPlanForTheRole() {
            assertThat(manager.planFor(runningAs("user")).view().getTableNames()).containsExactly(ORDERS);
            assertThat(manager.planFor(Principal.anonymous()).view().getTableNames()).isEmpty();
        }
    }
}
