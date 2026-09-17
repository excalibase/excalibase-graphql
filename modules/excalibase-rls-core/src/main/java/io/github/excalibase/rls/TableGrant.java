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

    /** A grant with no role is project-wide; otherwise the caller's role must match it. */
    public boolean appliesToRole(String callerRole) {
        if (role == null || role.isBlank()) {
            return true;
        }
        return role.equalsIgnoreCase(callerRole);
    }
}
