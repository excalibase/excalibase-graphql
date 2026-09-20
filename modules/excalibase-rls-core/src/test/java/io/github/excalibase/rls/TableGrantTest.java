package io.github.excalibase.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableGrantTest {

    private static TableGrant grant(Set<Operation> operations, String role, boolean enabled) {
        return new TableGrant("g1", "proj-1", "public.orders", operations, role, enabled);
    }

    @Nested
    @DisplayName("operations")
    class Operations {

        @Test
        void operations_whenNull_grantsNothing() {
            TableGrant grant = grant(null, "anon", true);

            assertThat(grant.operations()).isEmpty();
            assertThat(grant.appliesTo(Operation.SELECT)).isFalse();
        }

        @Test
        void operations_whenEmpty_grantsNothing() {
            TableGrant grant = grant(Set.of(), "anon", true);

            assertThat(Operation.ALL).allSatisfy(op -> assertThat(grant.appliesTo(op)).isFalse());
        }

        @Test
        void appliesTo_whenOperationListed_returnsTrue() {
            TableGrant grant = grant(Set.of(Operation.SELECT, Operation.INSERT), "anon", true);

            assertThat(grant.appliesTo(Operation.SELECT)).isTrue();
            assertThat(grant.appliesTo(Operation.INSERT)).isTrue();
            assertThat(grant.appliesTo(Operation.UPDATE)).isFalse();
            assertThat(grant.appliesTo(Operation.DELETE)).isFalse();
        }

        @Test
        void operations_whenMutatedAfterConstruction_doesNotAffectGrant() {
            Set<Operation> mutable = new HashSet<>(Set.of(Operation.SELECT));
            TableGrant grant = grant(mutable, "anon", true);
            mutable.add(Operation.DELETE);

            assertThat(grant.appliesTo(Operation.DELETE)).isFalse();
        }
    }

    @Nested
    @DisplayName("role matching")
    class RoleMatching {

        @Test
        void appliesToRole_whenGrantRoleIsNull_appliesToNobody() {
            TableGrant grant = grant(Set.of(Operation.SELECT), null, true);

            assertThat(grant.appliesToRole("authenticated")).isFalse();
            assertThat(grant.appliesToRole("anon")).isFalse();
            assertThat(grant.appliesToRole(null)).isFalse();
        }

        @Test
        void appliesToRole_whenGrantRoleIsBlank_appliesToNobody() {
            TableGrant grant = grant(Set.of(Operation.SELECT), "  ", true);

            assertThat(grant.appliesToRole("authenticated")).isFalse();
            assertThat(grant.appliesToRole("anon")).isFalse();
        }

        @Test
        void appliesToRole_whenGrantRoleIsAWildcard_isNotSpecialCased() {
            TableGrant grant = grant(Set.of(Operation.SELECT), "*", true);

            assertThat(grant.appliesToRole("authenticated")).isFalse();
            assertThat(grant.appliesToRole("anon")).isFalse();
        }

        @Test
        void appliesToRole_whenCallerRoleIsBlank_matchesNoGrant() {
            TableGrant grant = grant(Set.of(Operation.SELECT), "anon", true);

            assertThat(grant.appliesToRole("  ")).isFalse();
        }

        @Test
        void appliesToRole_whenRolesMatchIgnoringCase_returnsTrue() {
            TableGrant grant = grant(Set.of(Operation.SELECT), "Authenticated", true);

            assertThat(grant.appliesToRole("authenticated")).isTrue();
        }

        @Test
        void appliesToRole_whenRolesDiffer_returnsFalse() {
            TableGrant grant = grant(Set.of(Operation.SELECT), "authenticated", true);

            assertThat(grant.appliesToRole("anon")).isFalse();
        }

        @Test
        void appliesToRole_whenCallerHasNoRole_doesNotMatchARoleScopedGrant() {
            TableGrant grant = grant(Set.of(Operation.SELECT), "authenticated", true);

            assertThat(grant.appliesToRole(null)).isFalse();
        }
    }

    @Test
    void constructor_whenResourceIsNull_throws() {
        assertThatThrownBy(() -> new TableGrant("g1", "proj-1", null, Set.of(), "anon", true))
                .isInstanceOf(NullPointerException.class);
    }

    @Nested
    @DisplayName("TableGrants payload")
    class Payload {

        @Test
        void unenforced_whenAsked_reportsNotEnforcedAndNoGrants() {
            TableGrants grants = TableGrants.unenforced("proj-1");

            assertThat(grants.enforced()).isFalse();
            assertThat(grants.grants()).isEmpty();
        }

        @Test
        void enforced_whenGrantListIsEmpty_staysEnforced() {
            TableGrants grants = new TableGrants("proj-1", true, List.of());

            assertThat(grants.enforced()).isTrue();
            assertThat(grants.grants()).isEmpty();
        }

        @Test
        void enabledGrants_whenGrantDisabled_isExcluded() {
            TableGrant on = grant(Set.of(Operation.SELECT), null, true);
            TableGrant off = new TableGrant("g2", "proj-1", "public.secrets", Set.of(Operation.SELECT), null, false);

            TableGrants grants = new TableGrants("proj-1", true, List.of(on, off));

            assertThat(grants.enabledGrants()).containsExactly(on);
        }

        @Test
        void grants_whenNull_isEmptyRatherThanNull() {
            TableGrants grants = new TableGrants("proj-1", true, null);

            assertThat(grants.grants()).isEmpty();
        }
    }
}
