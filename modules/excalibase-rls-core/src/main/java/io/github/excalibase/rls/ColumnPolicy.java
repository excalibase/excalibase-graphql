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
        columns = (columns == null || columns.isEmpty()) ? Set.of() : Set.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("ColumnPolicy must target at least one column");
        }
        operations = (operations == null || operations.isEmpty()) ? Operation.ALL : Set.copyOf(operations);
        assignments = (assignments == null) ? List.of() : List.copyOf(assignments);
        validateMaskInputs(mode, partialSpec, customMaskerKey);
    }

    /** Ensures the optional mask inputs are present/absent as each mode requires. */
    private static void validateMaskInputs(MaskMode mode, PartialMaskSpec spec, String customKey) {
        boolean hasSpec = spec != null;
        boolean hasKey = customKey != null && !customKey.isBlank();
        switch (mode) {
            case PARTIAL -> require(hasSpec, "PARTIAL mode requires partialSpec");
            case CUSTOM -> require(hasKey, "CUSTOM mode requires customMaskerKey");
            case HIDE, NULL, HASH -> {
                require(!hasSpec, mode + " mode does not use partialSpec");
                require(customKey == null, mode + " mode does not use customMaskerKey");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public boolean appliesTo(Operation op) {
        return operations.contains(op);
    }
}
