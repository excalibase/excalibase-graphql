package io.github.excalibase.rls;

import io.github.excalibase.security.JwtClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A change event reaches a realtime subscriber only as far as the query path
 * would show it: each row image is checked on its own, an UPDATE carries its
 * images under "old" and "new", and hidden columns are removed from every image.
 */
class RlsChangeEventTest {

    private static final String PROJECT = "proj-a";
    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";

    @Test
    @DisplayName("an INSERT the subscriber owns is delivered with hidden columns removed")
    void insert_owned_deliveredMasked() {
        RlsPolicyEnforcer enforcer = enforcer(List.of(owner()), List.of(hideSecret()));
        Optional<Map<String, Object>> out = enforcer.renderChange(PROJECT, "notes", claims(ALICE), "INSERT", row(ALICE, "draft"));
        assertThat(out).isPresent();
        assertThat(out.get()).containsEntry("owner_id", ALICE).doesNotContainKey("secret");
    }

    @Test
    @DisplayName("an INSERT someone else owns is dropped")
    void insert_notOwned_dropped() {
        RlsPolicyEnforcer enforcer = enforcer(List.of(owner()), List.of());
        assertThat(enforcer.renderChange(PROJECT, "notes", claims(ALICE), "INSERT", row(BOB, "draft"))).isEmpty();
    }

    @Test
    @DisplayName("a DELETE whose owner arrives as NULL is dropped, for anonymous callers too")
    void delete_nullOwner_dropped() {
        RlsPolicyEnforcer enforcer = enforcer(List.of(owner()), List.of());
        assertThat(enforcer.renderChange(PROJECT, "notes", claims(ALICE), "DELETE", row(null, null))).isEmpty();
        assertThat(enforcer.renderChange(PROJECT, "notes", null, "DELETE", row(null, null))).isEmpty();
    }

    @Test
    @DisplayName("an UPDATE of the subscriber's own row keeps both images, each masked")
    void update_owned_bothImagesMasked() {
        RlsPolicyEnforcer enforcer = enforcer(List.of(owner()), List.of(hideSecret()));
        Optional<Map<String, Object>> out = enforcer.renderChange(PROJECT, "notes", claims(ALICE), "UPDATE",
            update(row(ALICE, "draft"), row(ALICE, "active")));
        assertThat(out).isPresent();
        assertThat(image(out.get(), "old")).containsEntry("status", "draft").doesNotContainKey("secret");
        assertThat(image(out.get(), "new")).containsEntry("status", "active").doesNotContainKey("secret");
    }

    @Test
    @DisplayName("an UPDATE that moves a row between owners shows each side only the image it may read")
    void update_reassigned_eachSideSeesItsImage() {
        RlsPolicyEnforcer enforcer = enforcer(List.of(owner()), List.of());
        Map<String, Object> change = update(row(ALICE, "draft"), row(BOB, "draft"));

        Map<String, Object> forAlice = enforcer.renderChange(PROJECT, "notes", claims(ALICE), "UPDATE", change).orElseThrow();
        Map<String, Object> forBob = enforcer.renderChange(PROJECT, "notes", claims(BOB), "UPDATE", change).orElseThrow();

        assertThat(forAlice).containsOnlyKeys("old");
        assertThat(forBob).containsOnlyKeys("new");
    }

    @Test
    @DisplayName("an UPDATE is judged on its row images, not on the wrapper that holds them")
    void update_judgedOnImages() {
        Policy notArchived = new Policy("p", "p", "notes", PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule("status", FieldType.STRING, RuleOperator.NEQ, "archived")), List.of(Assignment.all()));
        RlsPolicyEnforcer enforcer = enforcer(List.of(notArchived), List.of());
        Map<String, Object> change = update(row(ALICE, "archived"), row(ALICE, "archived"));
        assertThat(enforcer.renderChange(PROJECT, "notes", claims(ALICE), "UPDATE", change)).isEmpty();
    }

    @Test
    @DisplayName("an UPDATE on a table without row policies still has hidden columns removed from both images")
    void update_noRowPolicies_masked() {
        RlsPolicyEnforcer enforcer = enforcer(List.of(), List.of(hideSecret()));
        Map<String, Object> out = enforcer.renderChange(PROJECT, "notes", claims(ALICE), "UPDATE",
            update(row(ALICE, "draft"), row(ALICE, "active"))).orElseThrow();
        assertThat(image(out, "old")).doesNotContainKey("secret");
        assertThat(image(out, "new")).doesNotContainKey("secret");
    }

    @Test
    @DisplayName("a relationship policy the in-memory matcher cannot evaluate drops the event")
    void relationshipPolicy_dropped() {
        Policy member = new Policy("p", "p", "notes", PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(), List.of(new RelationPredicate("members", "note_id", "id", LogicOperator.AND, List.of())),
            List.of(Assignment.all()));
        RlsPolicyEnforcer enforcer = enforcer(List.of(member), List.of());
        assertThat(enforcer.renderChange(PROJECT, "notes", claims(ALICE), "INSERT", row(ALICE, "draft"))).isEmpty();
    }

    private static Policy owner() {
        return new Policy("owner", "owner", "notes", PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule("owner_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
            List.of(Assignment.all()));
    }

    private static ColumnPolicy hideSecret() {
        return new ColumnPolicy("c", "hide-secret", "notes", Set.of("secret"), Operation.ALL, MaskMode.HIDE,
            null, null, 0, true, List.of(Assignment.all()));
    }

    private static RlsPolicyEnforcer enforcer(List<Policy> policies, List<ColumnPolicy> columns) {
        InMemoryPolicyProvider provider = new InMemoryPolicyProvider();
        provider.put(PROJECT, policies);
        provider.putColumns(PROJECT, columns);
        return new RlsPolicyEnforcer(provider);
    }

    private static Map<String, Object> row(String owner, String status) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1);
        row.put("owner_id", owner);
        row.put("status", status);
        row.put("secret", "s3cret");
        return row;
    }

    private static Map<String, Object> update(Map<String, Object> oldImage, Map<String, Object> newImage) {
        Map<String, Object> change = new HashMap<>();
        change.put("old", oldImage);
        change.put("new", newImage);
        return change;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> image(Map<String, Object> change, String key) {
        return (Map<String, Object>) change.get(key);
    }

    private static JwtClaims claims(String userId) {
        return JwtClaims.of(userId, PROJECT, "acme", "demo", "app_authenticated", "a@x.com");
    }
}
