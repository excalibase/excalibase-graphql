package io.github.excalibase.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnMaskerTest {

    private static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static UserContext anon() {
        return ctx(null, null, Set.of("anon"), Set.of());
    }

    private static UserContext authenticated(UUID userId) {
        return ctx(userId.toString(), TENANT.toString(), Set.of("authenticated"), Set.of());
    }

    private static UserContext withRole(String role) {
        return ctx(ALICE.toString(), TENANT.toString(), Set.of(role), Set.of());
    }

    private static UserContext ctx(String uid, String tid, Set<String> roles, Set<String> groups) {
        return new UserContext() {
            @Override public String userId() { return uid; }
            @Override public String tenantId() { return tid; }
            @Override public Set<String> roles() { return roles; }
            @Override public Set<String> groupIds() { return groups; }
        };
    }

    @Nested
    @DisplayName("plan() composition")
    class Composition {

        @Test
        @DisplayName("no policies → empty plan (nothing hidden, nothing masked)")
        void noPolicies_emptyPlan() {
            ColumnMasker masker = new ColumnMasker(List.of());
            MaskingPlan plan = masker.plan("users", anon(), Operation.SELECT);
            assertThat(plan.hidden()).isEmpty();
            assertThat(plan.masked()).isEmpty();
        }

        @Test
        @DisplayName("HIDE policy → column appears in hidden() set, not in masked()")
        void hidePolicy_inHiddenSet() {
            ColumnPolicy hide = hidePolicy("hide-ssn", "users", Set.of("ssn"), Assignment.role("anon"));
            ColumnMasker masker = new ColumnMasker(List.of(hide));
            MaskingPlan plan = masker.plan("users", anon(), Operation.SELECT);
            assertThat(plan.hidden()).containsExactly("ssn");
            assertThat(plan.masked()).isEmpty();
        }

        @Test
        @DisplayName("NULL policy → column appears in masked() with mode NULL, not in hidden()")
        void nullPolicy_inMaskedSet() {
            ColumnPolicy nullify = nullPolicy("null-phone", "users", Set.of("phone"), Assignment.role("anon"));
            ColumnMasker masker = new ColumnMasker(List.of(nullify));
            MaskingPlan plan = masker.plan("users", anon(), Operation.SELECT);
            assertThat(plan.hidden()).isEmpty();
            assertThat(plan.masked()).containsEntry("phone", MaskMode.NULL);
        }

        @Test
        @DisplayName("most-restrictive wins: HIDE overrides NULL on the same column")
        void hideOverridesNull_onSameColumn() {
            ColumnPolicy nullify = nullPolicy("a", "users", Set.of("email"), Assignment.all());
            ColumnPolicy hide = hidePolicy("b", "users", Set.of("email"), Assignment.all());
            ColumnMasker masker = new ColumnMasker(List.of(nullify, hide));
            MaskingPlan plan = masker.plan("users", anon(), Operation.SELECT);
            assertThat(plan.hidden()).containsExactly("email");
            assertThat(plan.masked()).doesNotContainKey("email");
        }

        @Test
        @DisplayName("different columns can carry different modes simultaneously")
        void mixedColumns() {
            ColumnPolicy hideSsn = hidePolicy("hide-ssn", "users", Set.of("ssn"), Assignment.all());
            ColumnPolicy nullEmail = nullPolicy("null-email", "users", Set.of("email"), Assignment.all());
            ColumnMasker masker = new ColumnMasker(List.of(hideSsn, nullEmail));
            MaskingPlan plan = masker.plan("users", anon(), Operation.SELECT);
            assertThat(plan.hidden()).containsExactly("ssn");
            assertThat(plan.masked()).containsEntry("email", MaskMode.NULL);
        }

        @Test
        @DisplayName("a policy covering multiple columns applies to each")
        void multipleColumnsPerPolicy() {
            ColumnPolicy hidePii = hidePolicy("hide-pii", "users",
                Set.of("ssn", "phone", "address"), Assignment.all());
            ColumnMasker masker = new ColumnMasker(List.of(hidePii));
            MaskingPlan plan = masker.plan("users", anon(), Operation.SELECT);
            assertThat(plan.hidden()).containsExactlyInAnyOrder("ssn", "phone", "address");
        }
    }

    @Nested
    @DisplayName("assignment scoping")
    class AssignmentScoping {

        @Test
        @DisplayName("ROLE assignment: only applies to users in that role")
        void roleAssignment() {
            ColumnPolicy adminUnmask = hidePolicy("hide-from-non-admin", "users",
                Set.of("ssn"), Assignment.role("user"));
            ColumnMasker masker = new ColumnMasker(List.of(adminUnmask));

            // user role: policy in scope → ssn hidden
            assertThat(masker.plan("users", withRole("user"), Operation.SELECT).hidden())
                .containsExactly("ssn");
            // admin role: policy NOT in scope → ssn visible
            assertThat(masker.plan("users", withRole("admin"), Operation.SELECT).hidden())
                .isEmpty();
        }

        @Test
        @DisplayName("USER assignment: only applies to the specified user")
        void userAssignment() {
            UUID bob = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
            ColumnPolicy hideForAlice = hidePolicy("hide-for-alice", "users",
                Set.of("email"), Assignment.user(ALICE.toString()));
            ColumnMasker masker = new ColumnMasker(List.of(hideForAlice));

            assertThat(masker.plan("users", authenticated(ALICE), Operation.SELECT).hidden())
                .containsExactly("email");
            assertThat(masker.plan("users", authenticated(bob), Operation.SELECT).hidden())
                .isEmpty();
        }

        @Test
        @DisplayName("ALL assignment: applies to every subscriber")
        void allAssignment() {
            ColumnPolicy hideForEveryone = hidePolicy("hide-for-all", "users",
                Set.of("password_hash"), Assignment.all());
            ColumnMasker masker = new ColumnMasker(List.of(hideForEveryone));

            assertThat(masker.plan("users", anon(), Operation.SELECT).hidden())
                .containsExactly("password_hash");
            assertThat(masker.plan("users", authenticated(ALICE), Operation.SELECT).hidden())
                .containsExactly("password_hash");
        }
    }

    @Nested
    @DisplayName("operation scoping")
    class OperationScoping {

        @Test
        @DisplayName("SELECT-scoped column policy does not affect INSERT plan")
        void selectScoped_doesNotAffectInsert() {
            ColumnPolicy selectOnly = new ColumnPolicy(
                UUID.randomUUID().toString(), "select-only", "users",
                Set.of("ssn"), Set.of(Operation.SELECT), MaskMode.HIDE,
                null, null, 0, true, List.of(Assignment.all()));
            ColumnMasker masker = new ColumnMasker(List.of(selectOnly));

            assertThat(masker.plan("users", anon(), Operation.SELECT).hidden())
                .containsExactly("ssn");
            assertThat(masker.plan("users", anon(), Operation.INSERT).hidden())
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("disabled / different-resource policies")
    class Filtering {

        @Test
        @DisplayName("disabled policy is skipped entirely")
        void disabledPolicy_skipped() {
            ColumnPolicy disabled = new ColumnPolicy(
                UUID.randomUUID().toString(), "disabled", "users",
                Set.of("ssn"), Operation.ALL, MaskMode.HIDE,
                null, null, 0, /*enabled*/ false, List.of(Assignment.all()));
            ColumnMasker masker = new ColumnMasker(List.of(disabled));
            assertThat(masker.plan("users", anon(), Operation.SELECT).hidden()).isEmpty();
        }

        @Test
        @DisplayName("policy on a different resource doesn't bleed across")
        void differentResource_doesNotBleed() {
            ColumnPolicy onOther = hidePolicy("on-other", "audit_log",
                Set.of("ssn"), Assignment.all());
            ColumnMasker masker = new ColumnMasker(List.of(onOther));
            assertThat(masker.plan("users", anon(), Operation.SELECT).hidden()).isEmpty();
        }
    }

    @Nested
    @DisplayName("MaskingPlan.apply()")
    class ApplyToRow {

        @Test
        @DisplayName("apply removes HIDE columns from the row map")
        void apply_hide_removesEntry() {
            ColumnPolicy hide = hidePolicy("hide-ssn", "users", Set.of("ssn"), Assignment.all());
            MaskingPlan plan = new ColumnMasker(List.of(hide))
                .plan("users", anon(), Operation.SELECT);

            Map<String, Object> row = new HashMap<>(Map.of(
                "id", 1, "email", "x@y.com", "ssn", "123-45-6789"));
            plan.apply(row);
            assertThat(row).doesNotContainKey("ssn").containsKeys("id", "email");
        }

        @Test
        @DisplayName("apply nullifies NULL-mode columns in place")
        void apply_null_nullifies() {
            ColumnPolicy nullify = nullPolicy("null-email", "users",
                Set.of("email"), Assignment.all());
            MaskingPlan plan = new ColumnMasker(List.of(nullify))
                .plan("users", anon(), Operation.SELECT);

            Map<String, Object> row = new HashMap<>(Map.of(
                "id", 1, "email", "x@y.com"));
            plan.apply(row);
            assertThat(row).containsEntry("email", null).containsEntry("id", 1);
        }

        @Test
        @DisplayName("apply leaves unmasked columns alone")
        void apply_unmaskedColumns_untouched() {
            ColumnPolicy hide = hidePolicy("hide-ssn", "users", Set.of("ssn"), Assignment.all());
            MaskingPlan plan = new ColumnMasker(List.of(hide))
                .plan("users", anon(), Operation.SELECT);

            Map<String, Object> row = new HashMap<>(Map.of("id", 5, "name", "alice"));
            plan.apply(row);
            assertThat(row).containsExactlyInAnyOrderEntriesOf(Map.of("id", 5, "name", "alice"));
        }

        @Test
        @DisplayName("apply on a row that already lacks the hidden column is a no-op")
        void apply_missingColumn_noOp() {
            ColumnPolicy hide = hidePolicy("hide-ssn", "users", Set.of("ssn"), Assignment.all());
            MaskingPlan plan = new ColumnMasker(List.of(hide))
                .plan("users", anon(), Operation.SELECT);

            Map<String, Object> row = new HashMap<>(Map.of("id", 5));
            plan.apply(row);
            assertThat(row).containsExactlyInAnyOrderEntriesOf(Map.of("id", 5));
        }

        @Test
        @DisplayName("apply for PARTIAL throws in v1.6 (not yet implemented)")
        void apply_partial_throwsInV1_6() {
            ColumnPolicy partial = new ColumnPolicy(
                UUID.randomUUID().toString(), "partial", "users",
                Set.of("email"), Operation.ALL, MaskMode.PARTIAL,
                new PartialMaskSpec.KeepLast(4, '*'),
                null, 0, true, List.of(Assignment.all()));
            MaskingPlan plan = new ColumnMasker(List.of(partial))
                .plan("users", anon(), Operation.SELECT);

            Map<String, Object> row = new HashMap<>(Map.of("email", "x@y.com"));
            assertThatThrownBy(() -> plan.apply(row))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("PARTIAL");
        }

        @Test
        @DisplayName("apply for HASH throws in v1.6")
        void apply_hash_throwsInV1_6() {
            ColumnPolicy hash = new ColumnPolicy(
                UUID.randomUUID().toString(), "hash", "users",
                Set.of("email"), Operation.ALL, MaskMode.HASH,
                null, null, 0, true, List.of(Assignment.all()));
            MaskingPlan plan = new ColumnMasker(List.of(hash))
                .plan("users", anon(), Operation.SELECT);

            Map<String, Object> row = new HashMap<>(Map.of("email", "x@y.com"));
            assertThatThrownBy(() -> plan.apply(row))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("HASH");
        }

        @Test
        @DisplayName("apply for CUSTOM throws in v1.6")
        void apply_custom_throwsInV1_6() {
            ColumnPolicy custom = new ColumnPolicy(
                UUID.randomUUID().toString(), "custom", "users",
                Set.of("email"), Operation.ALL, MaskMode.CUSTOM,
                null, "my-masker", 0, true, List.of(Assignment.all()));
            MaskingPlan plan = new ColumnMasker(List.of(custom))
                .plan("users", anon(), Operation.SELECT);

            Map<String, Object> row = new HashMap<>(Map.of("email", "x@y.com"));
            assertThatThrownBy(() -> plan.apply(row))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("CUSTOM");
        }
    }

    @Nested
    @DisplayName("input validation")
    class InputValidation {

        @Test @DisplayName("null policies list treated as empty")
        void nullPolicies_treatedAsEmpty() {
            ColumnMasker masker = new ColumnMasker(null);
            assertThat(masker.plan("anything", anon(), Operation.SELECT).hidden()).isEmpty();
        }

        @Test @DisplayName("ColumnPolicy with no columns rejected at construction")
        void emptyColumns_rejected() {
            Set<String> columns = Set.of();
            List<Assignment> assignments = List.of(Assignment.all());
            assertThatThrownBy(() -> new ColumnPolicy(
                "id", "p", "users", columns, Operation.ALL, MaskMode.HIDE,
                null, null, 0, true, assignments))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("PARTIAL mode without spec rejected at construction")
        void partialNoSpec_rejected() {
            Set<String> columns = Set.of("c");
            List<Assignment> assignments = List.of(Assignment.all());
            assertThatThrownBy(() -> new ColumnPolicy(
                "id", "p", "users", columns, Operation.ALL, MaskMode.PARTIAL,
                null, null, 0, true, assignments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PARTIAL");
        }

        @Test @DisplayName("CUSTOM mode without key rejected at construction")
        void customNoKey_rejected() {
            Set<String> columns = Set.of("c");
            List<Assignment> assignments = List.of(Assignment.all());
            assertThatThrownBy(() -> new ColumnPolicy(
                "id", "p", "users", columns, Operation.ALL, MaskMode.CUSTOM,
                null, null, 0, true, assignments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CUSTOM");
        }

        @Test @DisplayName("HIDE/NULL/HASH with non-null partialSpec rejected")
        void wrongMode_withPartialSpec_rejected() {
            Set<String> columns = Set.of("c");
            PartialMaskSpec spec = new PartialMaskSpec.KeepFirst(3, '*');
            List<Assignment> assignments = List.of(Assignment.all());
            assertThatThrownBy(() -> new ColumnPolicy(
                "id", "p", "users", columns, Operation.ALL, MaskMode.HIDE,
                spec, null, 0, true, assignments))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ----- helpers -----

    private static ColumnPolicy hidePolicy(String name, String resource, Set<String> cols, Assignment a) {
        return new ColumnPolicy(
            UUID.randomUUID().toString(), name, resource, cols, Operation.ALL,
            MaskMode.HIDE, null, null, 0, true, List.of(a));
    }

    private static ColumnPolicy nullPolicy(String name, String resource, Set<String> cols, Assignment a) {
        return new ColumnPolicy(
            UUID.randomUUID().toString(), name, resource, cols, Operation.ALL,
            MaskMode.NULL, null, null, 0, true, List.of(a));
    }
}
