package io.github.excalibase.rls;

import java.util.Set;

public enum Operation {
    SELECT,
    INSERT,
    UPDATE,
    DELETE;

    public static final Set<Operation> ALL = Set.of(SELECT, INSERT, UPDATE, DELETE);
}
