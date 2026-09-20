package io.github.excalibase.schema;

import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.security.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine cache is keyed by project <em>and</em> role, because the schema a
 * caller is served depends on both. Eviction has to drop every role variant of a
 * project: a grant change that narrows one role must not leave that role's older,
 * wider schema behind.
 */
class GraphqlSchemaManagerCacheTest {

    private CountingSchemaManager manager;

    /** Stubs out introspection so the cache behaviour can be tested without a database. */
    private static final class CountingSchemaManager extends GraphqlSchemaManager {

        private final List<String> built = new ArrayList<>();

        private CountingSchemaManager() {
            super(null, null, 30, "postgres", DEFAULT_MAX_QUERY_DEPTH, 30, "", null, null, null);
        }

        @Override
        EngineState buildEngineState(String orgSlug, String projectId, String callerRole) {
            built.add(projectId + "/" + callerRole);
            return new EngineState(null, null, null, TableExposure.UNRESTRICTED);
        }
    }

    @BeforeEach
    void setUp() {
        manager = new CountingSchemaManager();
    }

    @Test
    void resolveEngineState_whenSameProjectAndRole_buildsOnce() {
        manager.resolveEngineState("acme", "proj-1", "authenticated");
        manager.resolveEngineState("acme", "proj-1", "authenticated");

        assertThat(manager.built).containsExactly("proj-1/authenticated");
    }

    @Test
    void resolveEngineState_whenRolesDiffer_buildsOneStatePerRole() {
        manager.resolveEngineState("acme", "proj-1", "authenticated");
        manager.resolveEngineState("acme", "proj-1", "anon");

        assertThat(manager.built).containsExactly("proj-1/authenticated", "proj-1/anon");
    }

    @Test
    void resolveEngineState_whenCallerHasNoRole_isItsOwnCacheEntry() {
        manager.resolveEngineState("acme", "proj-1", null);
        manager.resolveEngineState("acme", "proj-1", null);
        manager.resolveEngineState("acme", "proj-1", "anon");

        assertThat(manager.built).containsExactly("proj-1/null", "proj-1/anon");
    }

    @Test
    void evict_whenCalled_dropsEveryRoleVariantOfThatProject() {
        manager.resolveEngineState("acme", "proj-1", "authenticated");
        manager.resolveEngineState("acme", "proj-1", "anon");

        manager.evict("proj-1");
        manager.resolveEngineState("acme", "proj-1", "authenticated");
        manager.resolveEngineState("acme", "proj-1", "anon");

        assertThat(manager.built).containsExactly(
                "proj-1/authenticated", "proj-1/anon", "proj-1/authenticated", "proj-1/anon");
    }

    @Test
    void evict_whenCalled_leavesOtherProjectsCached() {
        manager.resolveEngineState("acme", "proj-1", "anon");
        manager.resolveEngineState("acme", "proj-2", "anon");

        manager.evict("proj-1");
        manager.resolveEngineState("acme", "proj-2", "anon");

        assertThat(manager.built).containsExactly("proj-1/anon", "proj-2/anon");
    }

    @Test
    void resolveEngineState_whenNoProject_returnsTheUnscopedStateWithoutBuilding() {
        assertThat(manager.resolveEngineState("acme", null, "anon")).isNull();
        assertThat(manager.built).isEmpty();
    }

    /**
     * Exposure is evaluated against whether the caller is signed in, not against the
     * free-form {@code role} claim — which defaults to "user" and so would match
     * neither of the two roles a grant may name.
     */
    @Nested
    @DisplayName("caller role derivation")
    class CallerRoleDerivation {

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
        void resolveEngineState_whenThereAreNoClaims_buildsForAnon() {
            manager.resolveEngineState((JwtClaims) null);

            assertThat(manager.built).containsExactly("proj-1/anon");
        }

        @Test
        void resolveEngineState_whenClaimsCarryTheDefaultFreeFormRole_buildsForAuthenticated() {
            manager.resolveEngineState(JwtClaims.of("u-1", "proj-1", "acme", "demo", "user", "u@x.com"));

            assertThat(manager.built).containsExactly("proj-1/authenticated");
        }

        @Test
        void resolveEngineState_whenFreeFormRolesDiffer_sharesOneAuthenticatedState() {
            manager.resolveEngineState(JwtClaims.of("u-1", "proj-1", "acme", "demo", "user", "a@x.com"));
            manager.resolveEngineState(JwtClaims.of("u-2", "proj-1", "acme", "demo", "app_admin", "b@x.com"));

            assertThat(manager.built).containsExactly("proj-1/authenticated");
        }

        @Test
        void resolveEngineState_whenSignedInAndAnonymous_areSeparateCacheEntries() {
            manager.resolveEngineState(JwtClaims.of("u-1", "proj-1", "acme", "demo", "user", "a@x.com"));
            manager.resolveEngineState((JwtClaims) null);

            assertThat(manager.built).containsExactly("proj-1/authenticated", "proj-1/anon");
        }
    }
}
