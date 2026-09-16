package io.github.excalibase.rls;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An exposure grant: this table is reachable at all, for these operations, by
 * these principals. Checked before {@link Policy} (rows) and
 * {@link ColumnPolicy} (columns) — a table nobody granted is invisible no
 * matter what row policies say.
 *
 * <p><b>Deliberately not a {@link Policy}.</b> The two types have opposite
 * defaults and must never be interchangeable: an ALLOW {@code Policy} with no
 * rules means "every row passes", while a missing {@code TableGrant} means
 * "denied". Reusing {@code Policy} here would let one mistaken reuse turn a
 * deny into a blanket allow and still read as correct in review.
 *
 * <p>Enforced in Java rather than delegated to database GRANTs so the same
 * semantics hold on every backend, including those with no comparable
 * native model.
 */
public record TableGrant(
    String id,
    String name,
    String resource,
    Set<Operation> operations,
    List<Assignment> assignments,
    boolean enabled
) {

    /** Resource value granting every table — an operator's explicit "expose it all". */
    public static final String ALL_RESOURCES = "*";

    public TableGrant {
        Objects.requireNonNull(resource, "resource");
        operations = (operations == null || operations.isEmpty()) ? Operation.ALL : Set.copyOf(operations);
        assignments = (assignments == null) ? List.of() : List.copyOf(assignments);
    }

    public boolean appliesTo(Operation op) {
        return operations.contains(op);
    }
}
