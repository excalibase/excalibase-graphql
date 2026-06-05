package io.github.excalibase.rls;

import java.util.List;
import java.util.Set;

/**
 * Computes a {@link MaskingPlan} for a given (resource, user, operation)
 * tuple from a fixed set of {@link ColumnPolicy} rules. Same shape as
 * {@link RowMatcher} for RLS but operating on column visibility / value
 * transformation instead of row predicates.
 *
 * <p>See RFC 0007 for the model. v1.6 implements composition for all five
 * MaskModes at the plan-building level; {@link MaskMode#PARTIAL},
 * {@link MaskMode#HASH}, and {@link MaskMode#CUSTOM} are not yet renderable
 * (the JDBC SQL emitter and the in-memory applier both throw on them).
 */
public class ColumnMasker {

    private final List<ColumnPolicy> policies;

    public ColumnMasker(List<ColumnPolicy> policies) {
        this.policies = (policies == null) ? List.of() : List.copyOf(policies);
    }

    public MaskingPlan plan(String resource, UserContext ctx, Operation op) {
        MaskingPlan.Builder b = new MaskingPlan.Builder();
        Set<String> roles = ctx.roles() == null ? Set.of() : ctx.roles();
        Set<String> groups = ctx.groupIds() == null ? Set.of() : ctx.groupIds();
        String userId = ctx.userId();

        for (ColumnPolicy p : policies) {
            if (!p.enabled() || !p.resource().equals(resource) || !p.appliesTo(op)
                    || !assignmentMatches(p, userId, roles, groups)) {
                continue;
            }
            for (String column : p.columns()) {
                b.put(column, p.mode(), p.partialSpec());
            }
        }
        return b.build();
    }

    private static boolean assignmentMatches(ColumnPolicy p, String userId,
                                              Set<String> roles, Set<String> groups) {
        for (Assignment a : p.assignments()) {
            switch (a.targetType()) {
                case ALL: return true;
                case USER: if (userId != null && userId.equals(a.targetId())) return true; break;
                case ROLE: if (roles.contains(a.targetId())) return true; break;
                case GROUP: if (groups.contains(a.targetId())) return true; break;
            }
        }
        return false;
    }
}
