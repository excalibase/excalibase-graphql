package io.github.excalibase.rls;

import java.util.Objects;
import java.util.Set;

/**
 * An exposure grant: one resource (table, view or function) is reachable at all,
 * for these operations, by callers holding {@code role}.
 *
 * <p><b>Deliberately not a {@link Policy}.</b> The two types have opposite
 * defaults and must never be interchangeable: an ALLOW {@code Policy} with no
 * rules means "every row passes", while a missing {@code TableGrant} means
 * "denied". Reusing {@code Policy} here would let one mistaken reuse turn a
 * deny into a blanket allow and still read as correct in review.
 */
public record TableGrant(
        String id,
        String projectId,
        String resource,
        Set<Operation> operations,
        String role,
        boolean enabled
) {

    public TableGrant {
        Objects.requireNonNull(resource, "resource");
        // Unspecified grants nothing. In an allow-list the safe reading of a
        // missing or empty operations field is "none", not "all" — a control
        // plane serialising an empty list must not silently confer writes.
        operations = (operations == null) ? Set.of() : Set.copyOf(operations);
    }

    public boolean appliesTo(Operation operation) {
        return operations.contains(operation);
    }

    /**
     * True only when this grant names the caller's role.
     *
     * <p>A grant with no role reaches nobody. In an allow-list an unspecified
     * field is "none", not "everyone" — the same reading the operations field
     * gets above — so a control plane that serialises a blank role cannot
     * silently publish a resource to every caller. There is no wildcard either:
     * a grant may name {@code anon} or {@code authenticated} and nothing else.
     */
    public boolean appliesToRole(String callerRole) {
        if (role == null || role.isBlank() || callerRole == null || callerRole.isBlank()) {
            return false;
        }
        return role.equalsIgnoreCase(callerRole);
    }
}
