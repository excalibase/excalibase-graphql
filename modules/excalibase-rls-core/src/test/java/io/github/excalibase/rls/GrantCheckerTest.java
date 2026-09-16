package io.github.excalibase.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GrantCheckerTest {

    private static UserContext caller(String userId, Set<String> roles) {
        return new UserContext() {
            @Override public String userId() { return userId; }
            @Override public String tenantId() { return "proj-1"; }
            @Override public Set<String> roles() { return roles; }
            @Override public Set<String> groupIds() { return Set.of(); }
        };
    }

    private static final UserContext ANON = caller(null, Set.of("anon"));
    private static final UserContext AUTHENTICATED =
            caller("11111111-1111-1111-1111-111111111111", Set.of("authenticated"));

    @Nested
    @DisplayName("deny by default")
    class DenyByDefault {

        @Test
        void permits_noGrants_denies() {
            GrantChecker checker = new GrantChecker(List.of());
            assertThat(checker.permits("public.docs", ANON, Operation.SELECT)).isFalse();
        }

        @Test
        void permits_nullGrantList_denies() {
            GrantChecker checker = new GrantChecker(null);
            assertThat(checker.permits("public.docs", AUTHENTICATED, Operation.SELECT)).isFalse();
        }

        @Test
        void permits_grantOnAnotherTable_denies() {
            GrantChecker checker = new GrantChecker(List.of(
                    grant("g1", "public.notes", Operation.ALL, Assignment.all())));
            assertThat(checker.permits("public.docs", ANON, Operation.SELECT)).isFalse();
        }

        @Test
        void permits_disabledGrant_denies() {
            TableGrant disabled = new TableGrant("g1", "g1", "public.docs",
                    Operation.ALL, List.of(Assignment.all()), false);
            assertThat(new GrantChecker(List.of(disabled)).permits("public.docs", ANON, Operation.SELECT))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("assignments")
    class Assignments {

        @Test
        void permits_grantToEveryone_allowsAnonymous() {
            GrantChecker checker = new GrantChecker(List.of(
                    grant("g1", "public.docs", Operation.ALL, Assignment.all())));
            assertThat(checker.permits("public.docs", ANON, Operation.SELECT)).isTrue();
        }

        @Test
        void permits_roleGrant_allowsThatRole() {
            GrantChecker checker = new GrantChecker(List.of(
                    grant("g1", "public.docs", Operation.ALL, Assignment.role("authenticated"))));
            assertThat(checker.permits("public.docs", AUTHENTICATED, Operation.SELECT)).isTrue();
        }

        @Test
        void permits_roleGrant_deniesOtherRole() {
            GrantChecker checker = new GrantChecker(List.of(
                    grant("g1", "public.docs", Operation.ALL, Assignment.role("authenticated"))));
            assertThat(checker.permits("public.docs", ANON, Operation.SELECT)).isFalse();
        }

        @Test
        void permits_userGrant_allowsThatUserOnly() {
            GrantChecker checker = new GrantChecker(List.of(
                    grant("g1", "public.docs", Operation.ALL,
                            Assignment.user("11111111-1111-1111-1111-111111111111"))));
            assertThat(checker.permits("public.docs", AUTHENTICATED, Operation.SELECT)).isTrue();
            assertThat(checker.permits("public.docs", ANON, Operation.SELECT)).isFalse();
        }
    }

    @Nested
    @DisplayName("operation granularity")
    class OperationGranularity {

        @Test
        void permits_selectOnlyGrant_allowsSelect() {
            GrantChecker checker = new GrantChecker(List.of(
                    grant("g1", "public.docs", Set.of(Operation.SELECT), Assignment.role("anon"))));
            assertThat(checker.permits("public.docs", ANON, Operation.SELECT)).isTrue();
        }

        @Test
        void permits_selectOnlyGrant_deniesUpdate() {
            GrantChecker checker = new GrantChecker(List.of(
                    grant("g1", "public.docs", Set.of(Operation.SELECT), Assignment.role("anon"))));
            assertThat(checker.permits("public.docs", ANON, Operation.UPDATE)).isFalse();
        }

        @Test
        void permits_separateGrantsPerRole_scopeWritesToAuthenticated() {
            GrantChecker checker = new GrantChecker(List.of(
                    grant("read", "public.docs", Set.of(Operation.SELECT), Assignment.role("anon")),
                    grant("write", "public.docs", Operation.ALL, Assignment.role("authenticated"))));
            assertThat(checker.permits("public.docs", ANON, Operation.INSERT)).isFalse();
            assertThat(checker.permits("public.docs", AUTHENTICATED, Operation.INSERT)).isTrue();
        }
    }

    @Nested
    @DisplayName("resource matching")
    class ResourceMatching {

        @Test
        void permits_bareTableName_matchesSchemaQualifiedKey() {
            GrantChecker checker = new GrantChecker(List.of(
                    grant("g1", "docs", Operation.ALL, Assignment.all())));
            assertThat(checker.permits("public.docs", ANON, Operation.SELECT)).isTrue();
        }

        @Test
        void permits_wildcardResource_matchesEveryTable() {
            GrantChecker checker = new GrantChecker(List.of(
                    grant("g1", TableGrant.ALL_RESOURCES, Operation.ALL, Assignment.all())));
            assertThat(checker.permits("public.docs", ANON, Operation.SELECT)).isTrue();
            assertThat(checker.permits("other.ledger", ANON, Operation.DELETE)).isTrue();
        }

        @Test
        void permits_nullResource_denies() {
            GrantChecker checker = new GrantChecker(List.of(
                    grant("g1", "public.docs", Operation.ALL, Assignment.all())));
            assertThat(checker.permits(null, ANON, Operation.SELECT)).isFalse();
        }
    }

    private static TableGrant grant(String id, String resource, Set<Operation> operations, Assignment assignment) {
        return new TableGrant(id, id, resource, operations, List.of(assignment), true);
    }
}
