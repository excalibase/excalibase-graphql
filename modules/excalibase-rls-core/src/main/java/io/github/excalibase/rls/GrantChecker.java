package io.github.excalibase.rls;

import java.util.List;
import java.util.Set;

/**
 * Answers "is this table exposed to this caller for this operation?" from a
 * fixed set of {@link TableGrant}s. Same shape as {@link RowMatcher} (rows) and
 * {@link ColumnMasker} (columns), and evaluated before both.
 *
 * <p>Deny by default: no matching grant means denied. An empty grant list is
 * therefore "nothing is exposed", never "everything is exposed" — the inverse
 * of how an empty policy list reads.
 */
public class GrantChecker {

    private final List<TableGrant> grants;

    public GrantChecker(List<TableGrant> grants) {
        this.grants = (grants == null) ? List.of() : List.copyOf(grants);
    }

    public boolean permits(String resource, UserContext ctx, Operation op) {
        if (resource == null || ctx == null || op == null) {
            return false;
        }
        Set<String> roles = ctx.roles() == null ? Set.of() : ctx.roles();
        Set<String> groups = ctx.groupIds() == null ? Set.of() : ctx.groupIds();
        String userId = ctx.userId();

        for (TableGrant grant : grants) {
            if (grant.enabled()
                    && matchesResource(grant.resource(), resource)
                    && grant.appliesTo(op)
                    && assignmentMatches(grant, userId, roles, groups)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesResource(String grantResource, String resource) {
        return TableGrant.ALL_RESOURCES.equals(grantResource)
                || ResourceMatcher.matches(grantResource, resource);
    }

    private static boolean assignmentMatches(TableGrant grant, String userId,
                                             Set<String> roles, Set<String> groups) {
        for (Assignment a : grant.assignments()) {
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
