package io.github.excalibase.rls;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A column-level security rule. Sibling of {@link Policy}; both carry
 * assignments and operation scoping but solve different problems:
 * <ul>
 *   <li>{@code Policy} answers "can this user see this row?"</li>
 *   <li>{@code ColumnPolicy} answers "of the row the user can see, which
 *       columns are visible in real form?"</li>
 * </ul>
 *
 * <p>See RFC 0007 for the design.
 */
public record ColumnPolicy(
    String id,
    String name,
    String resource,
    Set<String> columns,
    Set<Operation> operations,
    MaskMode mode,
    PartialMaskSpec partialSpec,
    String customMaskerKey,
    int priority,
    boolean enabled,
    List<Assignment> assignments
) {

    public ColumnPolicy {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(mode, "mode");
        columns = (columns == null || columns.isEmpty())
            ? Set.of()
            : Set.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("ColumnPolicy must target at least one column");
        }
        operations = (operations == null || operations.isEmpty()) ? Operation.ALL : Set.copyOf(operations);
        assignments = (assignments == null) ? List.of() : List.copyOf(assignments);

        switch (mode) {
            case PARTIAL -> {
                if (partialSpec == null) {
                    throw new IllegalArgumentException("PARTIAL mode requires partialSpec");
                }
            }
            case CUSTOM -> {
                if (customMaskerKey == null || customMaskerKey.isBlank()) {
                    throw new IllegalArgumentException("CUSTOM mode requires customMaskerKey");
                }
            }
            case HIDE, NULL, HASH -> {
                if (partialSpec != null) {
                    throw new IllegalArgumentException(mode + " mode does not use partialSpec");
                }
                if (customMaskerKey != null) {
                    throw new IllegalArgumentException(mode + " mode does not use customMaskerKey");
                }
            }
        }
    }

    public boolean appliesTo(Operation op) {
        return operations.contains(op);
    }
}
