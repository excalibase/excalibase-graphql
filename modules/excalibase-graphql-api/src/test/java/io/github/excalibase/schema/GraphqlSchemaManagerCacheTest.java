package io.github.excalibase.schema;

import org.junit.jupiter.api.BeforeEach;
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
}
