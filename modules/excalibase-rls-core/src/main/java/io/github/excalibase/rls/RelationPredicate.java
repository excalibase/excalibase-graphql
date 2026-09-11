package io.github.excalibase.rls;

import java.util.List;
import java.util.Objects;

/**
 * A relationship (subquery) predicate — the core of Postgres RLS that flat
 * column rules cannot express: "this row is visible iff a correlated row exists
 * in another table". Compiles to a portable
 * {@code EXISTS (SELECT 1 FROM related WHERE related.foreignKey = parent.parentKey [AND/OR subRules])}
 * which runs identically on Postgres and MySQL.
 *
 * <p>Example — membership-based access ("you can see an order if you belong to
 * its org"): {@code relatedResource="members", foreignKey="org_id",
 * parentKey="org_id", subRules=[user_id EQ {{currentUserId}}]}.
 *
 * <p>{@code subRules} are ordinary scalar {@link Rule}s evaluated against the
 * <em>related</em> table's columns and combined with {@code subLogic}.
 */
public record RelationPredicate(
    String relatedResource,
    String foreignKey,
    String parentKey,
    LogicOperator subLogic,
    List<Rule> subRules
) {
    public RelationPredicate {
        Objects.requireNonNull(relatedResource, "relatedResource");
        Objects.requireNonNull(foreignKey, "foreignKey");
        Objects.requireNonNull(parentKey, "parentKey");
        subLogic = (subLogic == null) ? LogicOperator.AND : subLogic;
        subRules = (subRules == null) ? List.of() : List.copyOf(subRules);
    }
}
