package io.github.excalibase.schema;

import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.rls.InMemoryPolicyProvider;
import io.github.excalibase.rls.PolicyProvider;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.Principal;
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
 * The engine cache is keyed by project <em>and</em> the role the request runs as,
 * because the schema a caller is served depends on both. Eviction has to drop every role variant of a
 * project: a grant change that narrows one role must not leave that role's older,
 * wider schema behind.
 */
class GraphqlSchemaManagerCacheTest {

    private CountingSchemaManager manager;

    /** Stubs out introspection so the cache behaviour can be tested without a database. */
    private static final class CountingSchemaManager extends GraphqlSchemaManager {

        private final List<String> built = new ArrayList<>();

        private CountingSchemaManager(PolicyProvider policyProvider) {
            super(null, null, 30, "postgres", DEFAULT_MAX_QUERY_DEPTH, 30, "", null, null, policyProvider);
        }

        @Override
        EngineState buildEngineState(String orgSlug, String projectId, String callerRole) {
            built.add(projectId + "/" + callerRole);
            return new EngineState(null, null, null, TableExposure.UNRESTRICTED, "public");
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
        manager = new CountingSchemaManager(new InMemoryPolicyProvider());
    }

    @Test
    void resolveEngineState_whenSameProjectAndRole_buildsOnce() {
        manager.resolveEngineState("acme", "proj-1", runningAs("user"));
        manager.resolveEngineState("acme", "proj-1", runningAs("user"));

        assertThat(manager.built).containsExactly("proj-1/user");
    }

    @Test
    void resolveEngineState_whenRolesDiffer_buildsOneStatePerRole() {
        manager.resolveEngineState("acme", "proj-1", runningAs("user"));
        manager.resolveEngineState("acme", "proj-1", Principal.anonymous());
        manager.resolveEngineState("acme", "proj-1", runningAs("editor"));

        assertThat(manager.built).containsExactly("proj-1/user", "proj-1/anon", "proj-1/editor");
    }

    @Test
    void resolveEngineState_whenServiceBypasses_buildsUnderTheServiceKey() {
        manager.resolveEngineState("acme", "proj-1", service());
        manager.resolveEngineState("acme", "proj-1", service());

        assertThat(manager.built).containsExactly("proj-1/service");
    }

    @Test
    void resolveEngineState_whenServiceActsAsUser_sharesTheUserState() {
        JwtClaims serviceClaims = service().claims();
        manager.resolveEngineState("acme", "proj-1", new Principal("user", false, serviceClaims, Map.of()));
        manager.resolveEngineState("acme", "proj-1", runningAs("user"));

        assertThat(manager.built).containsExactly("proj-1/user");
    }

    @Test
    void resolveEngineState_whenNoPrincipalButPermissionsApply_refusesToGuessARole() {
        assertThatThrownBy(() -> manager.resolveEngineState("acme", "proj-1", null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manager.built).isEmpty();
    }

    @Test
    void resolveEngineState_whenNoPrincipalAndNoPermissionSource_servesTheWholeSchema() {
        CountingSchemaManager insecureDev = new CountingSchemaManager(null);

        insecureDev.resolveEngineState("acme", "proj-1", null);

        assertThat(insecureDev.built).containsExactly("proj-1/service");
    }

    @Test
    void evict_whenCalled_dropsEveryRoleVariantOfThatProject() {
        manager.resolveEngineState("acme", "proj-1", runningAs("user"));
        manager.resolveEngineState("acme", "proj-1", Principal.anonymous());

        manager.evict("proj-1");
        manager.resolveEngineState("acme", "proj-1", runningAs("user"));
        manager.resolveEngineState("acme", "proj-1", Principal.anonymous());

        assertThat(manager.built).containsExactly(
                "proj-1/user", "proj-1/anon", "proj-1/user", "proj-1/anon");
    }

    @Test
    void evict_whenCalled_leavesOtherProjectsCached() {
        manager.resolveEngineState("acme", "proj-1", Principal.anonymous());
        manager.resolveEngineState("acme", "proj-2", Principal.anonymous());

        manager.evict("proj-1");
        manager.resolveEngineState("acme", "proj-2", Principal.anonymous());

        assertThat(manager.built).containsExactly("proj-1/anon", "proj-2/anon");
    }

    @Test
    void resolveEngineState_whenNoProject_returnsTheUnscopedStateWithoutBuilding() {
        assertThat(manager.resolveEngineState("acme", null, Principal.anonymous())).isNull();
        assertThat(manager.built).isEmpty();
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
        void resolveEngineState_whenAnonymous_buildsForAnon() {
            manager.resolveEngineState(Principal.anonymous());

            assertThat(manager.built).containsExactly("proj-1/anon");
        }

        @Test
        void resolveEngineState_whenSignedIn_buildsForTheActiveRole() {
            manager.resolveEngineState(runningAs("editor"));

            assertThat(manager.built).containsExactly("proj-1/editor");
        }

        @Test
        void resolveEngineState_whenSignedInAndAnonymous_areSeparateCacheEntries() {
            manager.resolveEngineState(runningAs("user"));
            manager.resolveEngineState(Principal.anonymous());

            assertThat(manager.built).containsExactly("proj-1/user", "proj-1/anon");
        }
    }
}
