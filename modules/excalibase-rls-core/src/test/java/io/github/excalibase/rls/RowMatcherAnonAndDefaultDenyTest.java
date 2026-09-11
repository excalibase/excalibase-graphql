package io.github.excalibase.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit "Postgres-RLS-equivalent" tests. The composition rule says
 * "an ALLOW policy existing for a resource turns RLS on for everyone";
 * these tests assert that on the safety-critical paths:
 *
 * <ul>
 *   <li>An anonymous subscriber cannot read rows protected by any policy.</li>
 *   <li>An authenticated user outside a policy's role/group/user assignment
 *       also gets default-deny, not implicit access.</li>
 *   <li>Operation-scoping is honored: a SELECT-only policy does not
 *       restrict INSERT (because RLS is "off" for INSERT on that resource).</li>
 *   <li>Disabled policies do not turn RLS on.</li>
 *   <li>Adding the first ALLOW to an unrestricted resource flips it into
 *       restrictive mode for every subscriber, not just the ones the
 *       policy targets.</li>
 * </ul>
 */
class RowMatcherAnonAndDefaultDenyTest {

    private static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static UserContext anon() {
        return ctx(null, null, Set.of("anon"), Set.of());
    }

    private static UserContext aliceAuth() {
        return ctx(ALICE.toString(), TENANT.toString(), Set.of("authenticated"), Set.of());
    }

    private static UserContext ctx(String userId, String tenantId, Set<String> roles, Set<String> groups) {
        return new UserContext() {
            @Override public String userId() { return userId; }
            @Override public String tenantId() { return tenantId; }
            @Override public Set<String> roles() { return roles; }
            @Override public Set<String> groupIds() { return groups; }
        };
    }

    @Test
    @DisplayName("anon sees nothing once any ALLOW exists, even if assigned to a role anon does not have")
    void anon_defaultDeny_whenAuthenticatedOnlyPolicyExists() {
        Policy authenticatedOnly = new Policy(
            UUID.randomUUID().toString(), "authenticated owner", "orders",
            PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
            List.of(Assignment.role("authenticated"))
        );
        RowMatcher matcher = new RowMatcher(List.of(authenticatedOnly));

        // anon: ALLOW exists for "orders" → RLS on. anon has no in-scope ALLOW → deny.
        assertThat(matcher.matches("orders", Map.of("user_id", ALICE.toString()),
            anon(), Operation.SELECT)).isFalse();

        // alice (authenticated, owner of the row): in scope → allowed.
        assertThat(matcher.matches("orders", Map.of("user_id", ALICE.toString()),
            aliceAuth(), Operation.SELECT)).isTrue();
    }

    @Test
    @DisplayName("anon CAN see rows when an ALLOW assigned to 'anon' grants it explicitly")
    void anon_allowed_whenPolicyExplicitlyTargetsAnon() {
        // The way to give anon read access (the public-read pattern):
        // an explicit ALLOW assigned to the "anon" role with the row condition.
        Policy publicReadable = new Policy(
            UUID.randomUUID().toString(), "public posts readable by anon", "posts",
            PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule("public", FieldType.BOOLEAN, RuleOperator.EQ, "true")),
            List.of(Assignment.role("anon"))
        );
        RowMatcher matcher = new RowMatcher(List.of(publicReadable));

        // anon, public row → allowed.
        assertThat(matcher.matches("posts", Map.of("public", true), anon(), Operation.SELECT)).isTrue();
        // anon, non-public row → in-scope ALLOW does not match → deny.
        assertThat(matcher.matches("posts", Map.of("public", false), anon(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("operation scoping: SELECT-only policy does not restrict INSERT")
    void operationScoping_selectOnlyPolicy_doesNotRestrictInsert() {
        Policy selectOnly = new Policy(
            UUID.randomUUID().toString(), "select owner", "orders",
            PolicyEffect.ALLOW, Set.of(Operation.SELECT), LogicOperator.AND, 0, true,
            List.of(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
            List.of(Assignment.role("authenticated"))
        );
        RowMatcher matcher = new RowMatcher(List.of(selectOnly));

        // SELECT path: RLS on, alice owns the row → allowed.
        assertThat(matcher.matches("orders", Map.of("user_id", ALICE.toString()),
            aliceAuth(), Operation.SELECT)).isTrue();
        // SELECT path: anon → RLS on → default-deny.
        assertThat(matcher.matches("orders", Map.of("user_id", ALICE.toString()),
            anon(), Operation.SELECT)).isFalse();
        // INSERT path: no ALLOW exists for INSERT on "orders" → RLS off → unrestricted.
        assertThat(matcher.matches("orders", Map.of("user_id", ALICE.toString()),
            anon(), Operation.INSERT)).isTrue();
    }

    @Test
    @DisplayName("adding the first ALLOW flips a previously-unrestricted resource into restrictive mode")
    void firstAllow_flipsResourceIntoRestrictiveMode() {
        // Before any policies, anon sees everything.
        RowMatcher empty = new RowMatcher(List.of());
        assertThat(empty.matches("orders", Map.of("user_id", ALICE.toString()),
            anon(), Operation.SELECT)).isTrue();

        // Adding even one ALLOW (for someone else) is enough to lock anon out.
        Policy authenticatedOwner = new Policy(
            UUID.randomUUID().toString(), "owner", "orders",
            PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
            List.of(Assignment.role("authenticated"))
        );
        RowMatcher withPolicy = new RowMatcher(List.of(authenticatedOwner));
        assertThat(withPolicy.matches("orders", Map.of("user_id", ALICE.toString()),
            anon(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("disabled policies do not turn RLS on — they are inert")
    void disabledPolicies_doNotEnableRls() {
        Policy disabled = new Policy(
            UUID.randomUUID().toString(), "owner-disabled", "orders",
            PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, /*enabled*/ false,
            List.of(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
            List.of(Assignment.role("authenticated"))
        );
        RowMatcher matcher = new RowMatcher(List.of(disabled));
        // The only policy is disabled → RLS off → anon sees everything.
        assertThat(matcher.matches("orders", Map.of("user_id", ALICE.toString()),
            anon(), Operation.SELECT)).isTrue();
    }

    @Test
    @DisplayName("DENY-only policy: RLS is off (no ALLOW), but DENY still subtracts — supports block-list pattern")
    void denyOnlyPolicy_doesNotEnableRls_butStillSubtracts() {
        Policy hideDrafts = new Policy(
            UUID.randomUUID().toString(), "hide drafts", "posts",
            PolicyEffect.DENY, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule("status", FieldType.STRING, RuleOperator.EQ, "draft")),
            List.of(Assignment.all())
        );
        RowMatcher matcher = new RowMatcher(List.of(hideDrafts));

        // No ALLOW exists → RLS "off" for the resource → published rows are unrestricted.
        assertThat(matcher.matches("posts", Map.of("status", "published"),
            anon(), Operation.SELECT)).isTrue();
        // DENY still subtracts: a row that matches a DENY is hidden, regardless of RLS state.
        assertThat(matcher.matches("posts", Map.of("status", "draft"),
            anon(), Operation.SELECT)).isFalse();
    }
}
