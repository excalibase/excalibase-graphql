package io.github.excalibase.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link InMemoryPolicyProvider} — the seedable, project-scoped
 * policy source used in tests and as the default when no remote policy
 * store is wired. The HTTP+NATS-backed provider is a separate concern.
 */
class InMemoryPolicyProviderTest {

    private static Policy allow(String resource) {
        return new Policy(
                "id-" + resource, "p-" + resource, resource,
                PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
                List.of(Assignment.all()));
    }

    @Test
    @DisplayName("returns the policies seeded for a project")
    void returnsSeededPolicies() {
        var provider = new InMemoryPolicyProvider();
        provider.put("proj-a", List.of(allow("orders")));

        List<Policy> got = provider.policiesFor("proj-a");

        assertThat(got).hasSize(1);
        assertThat(got.get(0).resource()).isEqualTo("orders");
    }

    @Test
    @DisplayName("unknown project yields an empty list, never null")
    void unknownProjectIsEmpty() {
        var provider = new InMemoryPolicyProvider();
        assertThat(provider.policiesFor("nope")).isEmpty();
    }

    @Test
    @DisplayName("null projectId yields an empty list")
    void nullProjectIsEmpty() {
        var provider = new InMemoryPolicyProvider();
        provider.put("proj-a", List.of(allow("orders")));
        assertThat(provider.policiesFor(null)).isEmpty();
    }

    @Test
    @DisplayName("policies are scoped per project — no cross-project bleed")
    void policiesAreProjectScoped() {
        var provider = new InMemoryPolicyProvider();
        provider.put("proj-a", List.of(allow("orders")));
        provider.put("proj-b", List.of(allow("invoices")));

        assertThat(provider.policiesFor("proj-a")).singleElement()
                .extracting(Policy::resource).isEqualTo("orders");
        assertThat(provider.policiesFor("proj-b")).singleElement()
                .extracting(Policy::resource).isEqualTo("invoices");
    }

    @Test
    @DisplayName("returned list is an unmodifiable snapshot")
    void returnedListIsImmutable() {
        var provider = new InMemoryPolicyProvider();
        provider.put("proj-a", List.of(allow("orders")));
        assertThat(provider.policiesFor("proj-a")).isUnmodifiable();
    }

    @Test
    @DisplayName("put replaces the prior policy set for a project")
    void putReplacesPriorSet() {
        var provider = new InMemoryPolicyProvider();
        provider.put("proj-a", List.of(allow("orders")));
        provider.put("proj-a", List.of(allow("invoices"), allow("orders")));

        assertThat(provider.policiesFor("proj-a")).hasSize(2);
    }
}
