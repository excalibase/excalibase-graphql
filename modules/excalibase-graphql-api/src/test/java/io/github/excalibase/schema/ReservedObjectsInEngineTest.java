package io.github.excalibase.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionSetParser;
import io.github.excalibase.schema.GraphqlSchemaManager.EngineState;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.Principal;
import io.github.excalibase.spi.SqlEngineFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine never serves an internal schema even when one reaches it: here the reflection itself contains
 * an internal table and function, and the permission document grants them, as a loader or a provisioning
 * source that forgot the rule would hand them over. Nothing of them is built into any role's engine.
 */
class ReservedObjectsInEngineTest {

    private static final String ORDERS = "public.orders";
    private static final String META = "excalibase_meta.t";
    private static final String META_FUNCTION = "excalibase_meta.read_t";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DOCUMENT = "{\"projectId\":\"proj-1\",\"version\":1,\"tables\":["
            + "{\"table\":\"public.orders\",\"role\":\"user\",\"select\":{\"filter\":{},\"columns\":[\"id\"]}},"
            + "{\"table\":\"excalibase_meta.t\",\"role\":\"user\",\"select\":{\"filter\":{},\"columns\":[\"id\"]}}],"
            + "\"functions\":[{\"function\":\"excalibase_meta.read_t\",\"exposedAs\":\"QUERY\","
            + "\"inferPermissions\":true,\"sessionArgument\":null}],"
            + "\"functionPermissions\":[{\"function\":\"excalibase_meta.read_t\",\"role\":\"user\"}]}";

    private GraphqlSchemaManager manager;

    /** Reflects a database whose reflection holds an internal table and function next to a normal table. */
    private static final class LeakyReflectionManager extends GraphqlSchemaManager {

        private LeakyReflectionManager(PermissionProvider permissions) {
            super(null, 30, "postgres", DEFAULT_MAX_QUERY_DEPTH, 30, "", null, null, permissions, true);
        }

        @Override
        Reflection reflect(String orgSlug, String projectId) {
            SchemaInfo schema = new SchemaInfo();
            for (String table : List.of(ORDERS, META)) {
                schema.setTableSchema(table, table.substring(0, table.indexOf('.')));
                schema.addColumn(table, "id", "integer");
                schema.addPrimaryKey(table, "id");
            }
            schema.addFunction(META_FUNCTION, new SchemaInfo.FunctionInfo("excalibase_meta", "read_t",
                    SchemaInfo.RoutineKind.FUNCTION, SchemaInfo.Volatility.STABLE, false, true, META, List.of(), 1));
            return new Reflection(schema, "public", "postgres", SqlEngineFactory.create("postgres"), null,
                    (sql, params) -> false);
        }
    }

    private static PermissionProvider document() {
        return new PermissionProvider() {
            @Override
            public PermissionSet permissionsFor(String projectId) {
                try {
                    return PermissionSetParser.parse(MAPPER.readTree(DOCUMENT));
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException(e);
                }
            }

            @Override
            public void evict(String projectId) {
                // nothing cached
            }
        };
    }

    private static Principal user() {
        JwtClaims claims = new JwtClaims("u-1", "proj-1", "acme", "demo", "", "user", "u@x.com", null, 0L,
                Map.of(), Set.of("user"));
        return new Principal("user", false, claims, Map.of());
    }

    private static Principal service() {
        JwtClaims claims = new JwtClaims("u-1", "proj-1", "acme", "demo", "", "service", "apikey:1",
                "service", 1L);
        return new Principal(Principal.SERVICE, true, claims, Map.of());
    }

    @BeforeEach
    void setUp() {
        manager = new LeakyReflectionManager(document());
    }

    @Test
    void aRuleNamingAnInternalTable_grantsNothing() {
        EngineState state = manager.resolveEngineState("acme", "proj-1", user());

        assertThat(state.compiler().schemaInfo().getTableNames()).containsExactly(ORDERS);
    }

    @Test
    void aTrackedFunctionInAnInternalSchema_isNotCallableByAnyRole() {
        assertThat(manager.resolveEngineState("acme", "proj-1", service()).plan().functions().all()).isEmpty();
        assertThat(manager.resolveEngineState("acme", "proj-1", user()).plan().functions().all()).isEmpty();
    }

    @Test
    void realtimeOnAnInternalTable_isRefused_evenForService() {
        assertThat(manager.realtime("acme", "proj-1", service(), META)).isEmpty();
        assertThat(manager.realtime("acme", "proj-1", service(), ORDERS)).isPresent();
    }

    @Test
    void isReserved_answersForTheBuiltInSchemas() {
        assertThat(manager.isReserved("excalibase_meta")).isTrue();
        assertThat(manager.isReserved("public")).isFalse();
    }
}
