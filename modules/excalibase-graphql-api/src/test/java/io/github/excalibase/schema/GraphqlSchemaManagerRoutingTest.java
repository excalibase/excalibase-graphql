package io.github.excalibase.schema;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.security.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * With tenant routing wired, a project is served from its own database and
 * nothing else; the configured database is never a stand-in for a project.
 */
class GraphqlSchemaManagerRoutingTest {

    private static final class RecordingManager extends GraphqlSchemaManager {
        private final List<String> tenantBuilds = new ArrayList<>();

        private RecordingManager(DynamicDataSourceManager dataSourceManager) {
            super(null, null, 30, "postgres", DEFAULT_MAX_QUERY_DEPTH, 30, "", null, dataSourceManager, null);
        }

        @Override
        EngineState buildTenantEngineState(String orgSlug, String projectId, String callerRole) {
            tenantBuilds.add(projectId);
            return new EngineState(null, null, null, TableExposure.UNRESTRICTED);
        }
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("an anonymous caller (no org) on a tenant deployment is served the tenant's database")
    void anonymousCaller_isServedTheTenantDatabase() {
        RecordingManager manager = new RecordingManager(mock(DynamicDataSourceManager.class));

        manager.resolveEngineState(null, "proj-a", "anon");

        assertThat(manager.tenantBuilds).containsExactly("proj-a");
    }

    @Test
    @DisplayName("a token's project never stands in for the path's")
    void tokenProject_isNotAFallbackForThePath() {
        RecordingManager manager = new RecordingManager(mock(DynamicDataSourceManager.class));

        manager.resolveEngineState(JwtClaims.of("u-1", "proj-from-token", "acme", "demo", "user", "u@x.com"));

        assertThat(manager.tenantBuilds).isEmpty();
    }
}
